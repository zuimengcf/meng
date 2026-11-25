package org.fcitx.fcitx5.android.script.qjs;

public class QjsException extends RuntimeException {
    /** Order must match the enum in qjs_jni.c. */
    public enum Kind { SCRIPT, TIMEOUT, INTERRUPTED, OOM, BAD_RESULT, INTERNAL, TOO_LARGE, CLOSED }

    private final Kind kind;

    public QjsException(int kind, String message) {
        this(kind >= 0 && kind < Kind.values().length ? Kind.values()[kind] : Kind.INTERNAL, message);
    }

    public QjsException(Kind kind, String message) {
        super(kind + ": " + message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
