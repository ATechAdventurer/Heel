import Foundation

/// Calls return immediately; events are drained by polling. Mirrors Android's NativeBridge
/// + ControlRuntime.Engine, calling the same C-ABI boundary (native/src/lib.rs, mod ios)
/// instead of JNI.
protocol NativeEngine {
    func initialize() -> String
    func submit(_ command: String) -> String
    func drain() -> String
}

struct DeviceEngine: NativeEngine {
    func initialize() -> String {
        release(lovense_native_initialize())
    }

    func submit(_ command: String) -> String {
        command.withCString { release(lovense_native_submit($0)) }
    }

    func drain() -> String {
        release(lovense_native_drain())
    }

    private func release(_ pointer: UnsafeMutablePointer<CChar>?) -> String {
        guard let pointer else { return "" }
        defer { lovense_native_free_string(pointer) }
        return String(cString: pointer)
    }
}
