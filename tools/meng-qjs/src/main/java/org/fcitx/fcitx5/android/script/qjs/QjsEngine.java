package org.fcitx.fcitx5.android.script.qjs;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;

/**
 * A sandboxed QuickJS-ng instance.
 *
 * <p>The engine is built without quickjs-libc: scripts have only standard
 * ECMAScript built-ins, no std/os modules, no I/O, no timers, and no host
 * functions. A plugin script defines a global function; the host calls it with
 * {@code (text: string, ctx: object)} and gets a string back.
 *
 * <p>Thread-safe: calls are serialized. {@link #interrupt()} may be called from
 * any thread to abort the running evaluation. Not reusable after {@link #close()}.
 * Run it inside an {@code android:isolatedProcess} service for OS-level isolation.
 */
public final class QjsEngine implements Closeable {
    /** Heap limit. Large lookup tables (e.g. pinyin) need room: ~5-10x their source size once parsed. */
    public static final long DEFAULT_MEMORY_LIMIT = 128L * 1024 * 1024;
    public static final long DEFAULT_STACK_SIZE = 256L * 1024;
    /** Max script source size for {@link #load}; separate from per-call input so big data tables fit. */
    public static final int DEFAULT_MAX_SOURCE_BYTES = 64 * 1024 * 1024;
    /** Max bytes of text / ctx per {@link #call}. Covers the clipboard's 384K-char cap in UTF-8. */
    public static final int DEFAULT_MAX_INPUT_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    /** Pass as any limit to disable that check (memory limit then only bounded by the process). */
    public static final int UNLIMITED = 0;

    private final Object callLock = new Object();
    private final Object handleLock = new Object();
    private long handle;
    private final int maxSourceBytes;
    private final int maxInputBytes;
    private final int maxOutputBytes;

    public QjsEngine() {
        this(DEFAULT_MEMORY_LIMIT, DEFAULT_STACK_SIZE, DEFAULT_MAX_SOURCE_BYTES,
                DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /** Kept for compatibility: source limit = max(input limit, default source limit). */
    public QjsEngine(long memoryLimitBytes, long stackSizeBytes, int maxInputBytes, int maxOutputBytes) {
        this(memoryLimitBytes, stackSizeBytes,
                maxInputBytes <= 0 ? UNLIMITED : Math.max(maxInputBytes, DEFAULT_MAX_SOURCE_BYTES),
                maxInputBytes, maxOutputBytes);
    }

    /** Limits &lt;= 0 mean unlimited. */
    public QjsEngine(long memoryLimitBytes, long stackSizeBytes, int maxSourceBytes,
                     int maxInputBytes, int maxOutputBytes) {
        this.maxSourceBytes = maxSourceBytes;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputBytes = maxOutputBytes;
        this.handle = QjsNative.nativeCreate(memoryLimitBytes, stackSizeBytes);
    }

    private static boolean over(int len, int limit) {
        return limit > 0 && len > limit;
    }

    public static String version() {
        return QjsNative.nativeVersion();
    }

    /** Evaluate plugin source (global script, strict mode). Defines functions. */
    public void load(String source, String filename, int timeoutMs) {
        byte[] src = source.getBytes(StandardCharsets.UTF_8);
        if (over(src.length, maxSourceBytes)) throw new QjsException(QjsException.Kind.TOO_LARGE, "source " + src.length);
        synchronized (callLock) {
            QjsNative.nativeLoad(requireHandle(), src, filename, timeoutMs);
        }
    }

    /**
     * Call global {@code function(text, ctx)}. {@code ctxJson} must be a JSON
     * object string (or null for {}). Returns the string the function returned.
     */
    public String call(String function, String text, String ctxJson, int timeoutMs) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] c = ctxJson == null ? new byte[0] : ctxJson.getBytes(StandardCharsets.UTF_8);
        if (over(t.length, maxInputBytes) || over(c.length, maxInputBytes)) {
            throw new QjsException(QjsException.Kind.TOO_LARGE, "input " + t.length + "/" + c.length);
        }
        byte[] out;
        synchronized (callLock) {
            out = QjsNative.nativeCall(requireHandle(), function, t, c, timeoutMs);
        }
        if (out == null) throw new QjsException(QjsException.Kind.INTERNAL, "null result");
        if (over(out.length, maxOutputBytes)) throw new QjsException(QjsException.Kind.TOO_LARGE, "output " + out.length);
        return new String(out, StandardCharsets.UTF_8);
    }

    /** Abort the evaluation currently running (if any). Safe from any thread. */
    public void interrupt() {
        synchronized (handleLock) {
            if (handle != 0) QjsNative.nativeInterrupt(handle);
        }
    }

    /** Bytes currently allocated by this runtime. */
    public long memoryUsed() {
        synchronized (callLock) {
            return QjsNative.nativeMemoryUsed(requireHandle());
        }
    }

    @Override
    public void close() {
        interrupt();
        synchronized (callLock) {
            synchronized (handleLock) {
                if (handle != 0) {
                    QjsNative.nativeDestroy(handle);
                    handle = 0;
                }
            }
        }
    }

    private long requireHandle() {
        synchronized (handleLock) {
            if (handle == 0) throw new QjsException(QjsException.Kind.CLOSED, "engine closed");
            return handle;
        }
    }
}
