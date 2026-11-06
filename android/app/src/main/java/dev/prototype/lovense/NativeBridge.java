package dev.prototype.lovense;

/** Calls return immediately. Events are drained by the connection service's runtime. */
final class NativeBridge {
    static { System.loadLibrary("lovense_native"); }
    private NativeBridge() {}
    static native String initialize();
    static native String submit(String command);
    static native String drain();
}
