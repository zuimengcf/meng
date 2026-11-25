/*
 * meng-qjs: minimal, sandboxed QuickJS-ng JNI bridge.
 *
 * - Built from the quickjs-ng amalgamation WITHOUT QJS_BUILD_LIBC:
 *   scripts have no std/os module, no file/network/process/timer access.
 * - No host functions are registered. A script can only define a global
 *   function; the host calls it with (text: string, ctx: object) and
 *   expects a string back.
 * - Per-runtime memory limit, stack limit, and a deadline/interrupt flag
 *   checked by the QuickJS interrupt handler.
 *
 * SPDX-License-Identifier: MIT
 */
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

#define EXC_CLASS "org/fcitx/fcitx5/android/script/qjs/QjsException"

/* error kinds, must match QjsException.Kind ordinal */
enum {
    KIND_SCRIPT = 0,    /* JS threw / syntax error */
    KIND_TIMEOUT = 1,   /* deadline exceeded */
    KIND_INTERRUPTED = 2,
    KIND_OOM = 3,
    KIND_BAD_RESULT = 4,/* function missing or returned non-string */
    KIND_INTERNAL = 5,
};

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    _Atomic int interrupt;     /* set from any thread */
    int64_t deadline_ns;       /* 0 = none; only touched on the calling thread */
    int timed_out;
} Engine;

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int interrupt_handler(JSContext *ctx, void *opaque) {
    (void) ctx;
    Engine *e = (Engine *) opaque;
    if (atomic_load_explicit(&e->interrupt, memory_order_relaxed)) return 1;
    if (e->deadline_ns != 0 && now_ns() > e->deadline_ns) {
        e->timed_out = 1;
        return 1;
    }
    return 0;
}

static void throw_java(JNIEnv *env, int kind, const char *msg) {
    jclass cls = (*env)->FindClass(env, EXC_CLASS);
    if (!cls) return; /* NoClassDefFoundError already pending */
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) return;
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "");
    jobject ex = (*env)->NewObject(env, cls, ctor, (jint) kind, jmsg);
    if (ex) (*env)->Throw(env, (jthrowable) ex);
}

/* Take the pending JS exception, map it to a Java exception. */
static void throw_from_js(JNIEnv *env, Engine *e) {
    JSContext *ctx = e->ctx;
    JSValue exc = JS_GetException(ctx);
    int kind = KIND_SCRIPT;
    if (e->timed_out) kind = KIND_TIMEOUT;
    else if (atomic_load(&e->interrupt)) kind = KIND_INTERRUPTED;

    char buf[1024];
    buf[0] = 0;
    const char *m = JS_ToCString(ctx, exc);
    if (m) {
        if (kind == KIND_SCRIPT && strstr(m, "out of memory")) kind = KIND_OOM;
        strncat(buf, m, sizeof(buf) - 1);
        JS_FreeCString(ctx, m);
    }
    if (kind == KIND_SCRIPT && JS_IsError(exc)) {
        JSValue st = JS_GetPropertyStr(ctx, exc, "stack");
        if (JS_IsString(st)) {
            const char *s = JS_ToCString(ctx, st);
            if (s) {
                size_t used = strlen(buf);
                if (used + 2 < sizeof(buf)) {
                    strncat(buf, "\n", sizeof(buf) - used - 1);
                    strncat(buf, s, sizeof(buf) - strlen(buf) - 1);
                }
                JS_FreeCString(ctx, s);
            }
        }
        JS_FreeValue(ctx, st);
    }
    JS_FreeValue(ctx, exc);
    throw_java(env, kind, buf[0] ? buf : "exception");
}

/* Copy a Java byte[] to a NUL-terminated malloc buffer (QuickJS requires it). */
static char *dup_bytes(JNIEnv *env, jbyteArray arr, size_t *out_len) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char *p = (char *) malloc((size_t) n + 1);
    if (!p) return NULL;
    if (n > 0) (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *) p);
    p[n] = 0;
    *out_len = (size_t) n;
    return p;
}

static void arm(Engine *e, jint timeout_ms) {
    atomic_store(&e->interrupt, 0);
    e->timed_out = 0;
    e->deadline_ns = timeout_ms > 0 ? now_ns() + (int64_t) timeout_ms * 1000000LL : 0;
    JS_UpdateStackTop(e->rt); /* caller thread may differ between calls */
}

static void disarm(Engine *e) {
    e->deadline_ns = 0;
}

#define JFN(name) Java_org_fcitx_fcitx5_android_script_qjs_QjsNative_##name

JNIEXPORT jlong JNICALL
JFN(nativeCreate)(JNIEnv *env, jclass clazz, jlong mem_limit, jlong stack_size) {
    (void) clazz;
    Engine *e = (Engine *) calloc(1, sizeof(Engine));
    if (!e) { throw_java(env, KIND_OOM, "calloc"); return 0; }
    e->rt = JS_NewRuntime();
    if (!e->rt) { free(e); throw_java(env, KIND_INTERNAL, "JS_NewRuntime"); return 0; }
    if (mem_limit > 0) JS_SetMemoryLimit(e->rt, (size_t) mem_limit);
    if (stack_size > 0) JS_SetMaxStackSize(e->rt, (size_t) stack_size);
    JS_SetCanBlock(e->rt, false);
    JS_SetInterruptHandler(e->rt, interrupt_handler, e);
    e->ctx = JS_NewContext(e->rt); /* standard ECMAScript intrinsics only, no libc */
    if (!e->ctx) {
        JS_FreeRuntime(e->rt);
        free(e);
        throw_java(env, KIND_INTERNAL, "JS_NewContext");
        return 0;
    }
    return (jlong) (intptr_t) e;
}

JNIEXPORT void JNICALL
JFN(nativeLoad)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray source,
                jstring jfilename, jint timeout_ms) {
    (void) clazz;
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e) { throw_java(env, KIND_INTERNAL, "closed"); return; }
    size_t len = 0;
    char *src = dup_bytes(env, source, &len);
    if (!src) { throw_java(env, KIND_OOM, "malloc"); return; }
    const char *fname = jfilename ? (*env)->GetStringUTFChars(env, jfilename, NULL) : NULL;

    arm(e, timeout_ms);
    JSValue r = JS_Eval(e->ctx, src, len, fname ? fname : "<plugin>",
                        JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_STRICT);
    disarm(e);

    if (fname) (*env)->ReleaseStringUTFChars(env, jfilename, fname);
    free(src);
    if (JS_IsException(r)) throw_from_js(env, e);
    JS_FreeValue(e->ctx, r);
}

JNIEXPORT jbyteArray JNICALL
JFN(nativeCall)(JNIEnv *env, jclass clazz, jlong handle, jstring jfn,
                jbyteArray text, jbyteArray ctx_json, jint timeout_ms) {
    (void) clazz;
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e) { throw_java(env, KIND_INTERNAL, "closed"); return NULL; }
    JSContext *ctx = e->ctx;
    jbyteArray out = NULL;

    const char *fn_name = (*env)->GetStringUTFChars(env, jfn, NULL);
    size_t tlen = 0, clen = 0;
    char *tbuf = dup_bytes(env, text, &tlen);
    char *cbuf = dup_bytes(env, ctx_json, &clen);
    if (!fn_name || !tbuf || !cbuf) {
        throw_java(env, KIND_OOM, "malloc");
        goto done;
    }

    JSValue global = JS_GetGlobalObject(ctx);
    JSValue fn = JS_GetPropertyStr(ctx, global, fn_name);
    JS_FreeValue(ctx, global);
    if (!JS_IsFunction(ctx, fn)) {
        JS_FreeValue(ctx, fn);
        throw_java(env, KIND_BAD_RESULT, "function not defined");
        goto done;
    }

    arm(e, timeout_ms);
    JSValue args[2];
    args[0] = JS_NewStringLen(ctx, tbuf, tlen);
    args[1] = clen > 0 ? JS_ParseJSON(ctx, cbuf, clen, "<ctx>") : JS_NewObject(ctx);
    JSValue r;
    if (JS_IsException(args[0]) || JS_IsException(args[1])) {
        r = JS_EXCEPTION;
    } else {
        r = JS_Call(ctx, fn, JS_UNDEFINED, 2, args);
    }
    disarm(e);
    JS_FreeValue(ctx, args[0]);
    JS_FreeValue(ctx, args[1]);
    JS_FreeValue(ctx, fn);

    if (JS_IsException(r)) {
        throw_from_js(env, e);
    } else if (!JS_IsString(r)) {
        JS_FreeValue(ctx, r);
        throw_java(env, KIND_BAD_RESULT, "result is not a string");
    } else {
        size_t rlen = 0;
        const char *s = JS_ToCStringLen(ctx, &rlen, r);
        JS_FreeValue(ctx, r);
        if (!s) {
            throw_from_js(env, e);
        } else {
            out = (*env)->NewByteArray(env, (jsize) rlen);
            if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) rlen, (const jbyte *) s);
            JS_FreeCString(ctx, s);
        }
    }
    /* drop anything queued (Promises etc. are not supported) */
    JS_RunGC(e->rt);

done:
    if (fn_name) (*env)->ReleaseStringUTFChars(env, jfn, fn_name);
    free(tbuf);
    free(cbuf);
    return out;
}

JNIEXPORT void JNICALL
JFN(nativeInterrupt)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    Engine *e = (Engine *) (intptr_t) handle;
    if (e) atomic_store(&e->interrupt, 1);
}

JNIEXPORT jlong JNICALL
JFN(nativeMemoryUsed)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e) return 0;
    JSMemoryUsage u;
    JS_ComputeMemoryUsage(e->rt, &u);
    return (jlong) u.malloc_size;
}

JNIEXPORT void JNICALL
JFN(nativeDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e) return;
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt);
    free(e);
}

JNIEXPORT jstring JNICALL
JFN(nativeVersion)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, JS_GetVersion());
}
