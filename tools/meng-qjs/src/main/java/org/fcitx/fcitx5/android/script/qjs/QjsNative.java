package org.fcitx.fcitx5.android.script.qjs;

/** Raw JNI entry points. Use {@link QjsEngine} instead. */
final class QjsNative {
    static {
        System.loadLibrary("mengqjs");
    }

    private QjsNative() {}

    static native long nativeCreate(long memLimitBytes, long stackBytes);

    static native void nativeLoad(long handle, byte[] sourceUtf8, String filename, int timeoutMs);

    static native byte[] nativeCall(long handle, String function, byte[] textUtf8, byte[] ctxJsonUtf8, int timeoutMs);

    static native void nativeInterrupt(long handle);

    static native long nativeMemoryUsed(long handle);

    static native void nativeDestroy(long handle);

    static native String nativeVersion();
}
