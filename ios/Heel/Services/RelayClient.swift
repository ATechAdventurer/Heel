import Foundation

protocol RelayListener: AnyObject {
    func relayReceived(_ message: JSONDictionary)
    func relayConnectionChanged(connected: Bool, reason: String)
}

/// The slice of RelayClient both roles' session logic drives, so it can be exercised
/// without a socket.
@MainActor
protocol SessionRelay: AnyObject {
    var connected: Bool { get }
    /// The app-wide listener: it keeps receiving while no screen is showing.
    func setController(_ value: RelayListener?)
    @discardableResult func send(_ message: JSONDictionary) -> Bool
    func pause()
    func stop()
    func connect(enrollment: String?)
}

@MainActor
protocol OwnerRelay: SessionRelay {
    func frame(_ value: JSONDictionary, priorityZero: Bool)
}

@MainActor
protocol PetRelay: SessionRelay {
    func safeWord()
    func clearSafeWordForReady()
    func blockRetries(_ blocked: Bool)
    func disconnect(reason: String)
}

/// Owns the relay socket. Retrying a transport never replays a control frame. Main-thread only,
/// matching Android's Handler(mainLooper)-confined RelayClient.
@MainActor
final class RelayClient: NSObject {
    static let shared = RelayClient()

    private let store: CredentialStore
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    private let registrationSession = URLSession(configuration: .default)

    private weak var listener: RelayListener?
    private weak var controller: RelayListener?

    private var wanted = false
    private var retryBlocked = false
    private var retries = 0
    private var pendingStop: String?
    private var socketTask: URLSessionWebSocketTask?
    private var taskGeneration: [ObjectIdentifier: Int] = [:]
    private var registrationTask: URLSessionDataTask?
    private var generation = 0
    private(set) var connected = false
    private var lastLease: TimeInterval = 0
    private var lastFrame: TimeInterval = 0
    private var pendingFrame: JSONDictionary?
    private(set) var state: JSONDictionary = ["type": "state", "phase": "unpaired"]
    private(set) var connectionReason = "Not connected"

    private var reconnectWorkItem: DispatchWorkItem?
    private var heartbeatWorkItem: DispatchWorkItem?
    private var flushWorkItem: DispatchWorkItem?

    init(store: CredentialStore = CredentialStore()) {
        self.store = store
    }

    var hasToken: Bool { store.hasToken }
    var endpoint: String { store.endpoint }

    /// Pairing may already own a connected socket before the control runtime exists.
    /// Replay transport + current state through the normal authority checks, never a grant.
    func setController(_ value: RelayListener?) {
        controller = value
        value?.relayConnectionChanged(connected: connected, reason: connectionReason)
        if connected { value?.relayReceived(state) }
    }

    func attach(_ value: RelayListener) {
        listener = value
        value.relayConnectionChanged(connected: connected, reason: connectionReason)
        if connected { value.relayReceived(state) }
    }

    func detach(_ value: RelayListener) {
        if listener === value { listener = nil }
    }

    func blockRetries(_ blocked: Bool) {
        retryBlocked = blocked
        if blocked { reconnectWorkItem?.cancel() }
    }

    func networkAvailable() {
        if wanted, !retryBlocked, !connected {
            reconnectWorkItem?.cancel()
            scheduleReconnect(delay: 0)
        }
    }

    private func scheduleReconnect(delay: TimeInterval) {
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.wanted, !self.retryBlocked else { return }
            self.connect(enrollment: nil)
        }
        reconnectWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func retry() {
        reconnectWorkItem?.cancel()
        guard wanted, !retryBlocked, hasToken else { return }
        let delays: [TimeInterval] = [0.25, 0.5, 1, 2, 4, 5]
        scheduleReconnect(delay: delays[min(retries, delays.count - 1)])
        retries += 1
    }

    enum EndpointError: Error, LocalizedError {
        case invalid
        var errorDescription: String? { "Use an HTTPS server address, without a path or password." }
    }

    /// HTTPS only, no userinfo, no query, and no path beyond "/" — validated the same way
    /// Android checks the OkHttp-parsed HttpUrl before accepting a new relay address.
    nonisolated static func normalizedEndpoint(_ value: String) throws -> String {
        guard let components = URLComponents(string: value),
              components.scheme == "https",
              components.host != nil,
              (components.user ?? "").isEmpty,
              (components.password ?? "").isEmpty,
              components.query == nil,
              components.path.isEmpty || components.path == "/",
              let url = components.url
        else { throw EndpointError.invalid }
        var result = url.absoluteString
        while result.hasSuffix("/") { result.removeLast() }
        return result
    }

    func setEndpoint(_ value: String) throws {
        let normalized = try Self.normalizedEndpoint(value)
        wanted = false
        disconnect(reason: "Server changed")
        store.setEndpoint(normalized)
        state = ["type": "state", "phase": "unpaired"]
    }

    func connect(enrollment: String?) {
        wanted = true
        guard !connected, socketTask == nil, registrationTask == nil else { return }
        guard !endpoint.isEmpty else {
            wanted = false
            report(false, "Set your relay address first.")
            return
        }
        generation += 1
        let current = generation
        report(false, "Connecting…")

        let existingToken: String?
        do {
            existingToken = try store.token()
        } catch {
            report(false, "Phone identity unavailable. Use relay settings to reset this phone.")
            return
        }
        if let token = existingToken {
            open(token: token, generation: current)
            return
        }
        guard let enrollment, !enrollment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            report(false, "Enter this phone's setup code once.")
            return
        }
        guard let url = URL(string: endpoint + "/v1/register") else {
            report(false, "Set your relay address first.")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(enrollment.trimmingCharacters(in: .whitespacesAndNewlines), forHTTPHeaderField: "X-Heel-Enrollment")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data()
        request.timeoutInterval = 15

        let task = registrationSession.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self, current == self.generation else { return }
                self.registrationTask = nil
                if error != nil {
                    self.report(false, "Connection failed. Check the server and internet connection.")
                    return
                }
                guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    self.report(false, "Setup code was not accepted (\(code)).")
                    return
                }
                guard let data, data.count <= 4096,
                      let json = try? JSONSerialization.jsonObject(with: data) as? JSONDictionary,
                      let token = json["token"] as? String
                else {
                    self.report(false, "Invalid setup response.")
                    return
                }
                do {
                    try self.store.setToken(token)
                    if self.listener != nil { self.open(token: token, generation: current) }
                    else { self.report(false, "Phone setup complete") }
                } catch {
                    self.report(false, "Could not save phone identity. Ask for a replacement setup code.")
                }
            }
        }
        registrationTask = task
        task.resume()
    }

    private func open(token: String, generation current: Int) {
        guard let url = URL(string: endpoint + "/v1/ws") else { return }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let task = session.webSocketTask(with: request)
        socketTask = task
        taskGeneration[ObjectIdentifier(task)] = current
        task.resume()
        receiveLoop(task: task, generation: current)
    }

    private func receiveLoop(task: URLSessionWebSocketTask, generation current: Int) {
        task.receive { [weak self] result in
            guard let self else { return }
            guard case .success(let message) = result else { return } // failure surfaces via the task delegate
            let receivedAt = ProcessInfo.processInfo.systemUptime
            guard case .string(let text) = message else {
                DispatchQueue.main.async { self.continueReceiving(task: task, generation: current) }
                return
            }
            if text.utf8.count > 32768 {
                task.cancel(with: .messageTooBig, reason: nil)
                return
            }
            DispatchQueue.main.async {
                self.handleMessage(text: text, receivedAt: receivedAt, generation: current)
                self.continueReceiving(task: task, generation: current)
            }
        }
    }

    private func continueReceiving(task: URLSessionWebSocketTask, generation current: Int) {
        guard taskGeneration[ObjectIdentifier(task)] == current else { return }
        receiveLoop(task: task, generation: current)
    }

    private func handleMessage(text: String, receivedAt: TimeInterval, generation current: Int) {
        guard current == generation, connected else { return }
        guard let data = text.data(using: .utf8),
              let message = try? JSONSerialization.jsonObject(with: data) as? JSONDictionary,
              !message.optString("type").isEmpty
        else {
            disconnect(reason: "Invalid relay message · stopped")
            return
        }
        let type = message.optString("type")
        if type == "frame", ProcessInfo.processInfo.systemUptime - receivedAt > 0.5 {
            disconnect(reason: "Control delayed · stopped")
            return
        }
        if type == "lease" {
            lastLease = ProcessInfo.processInfo.systemUptime
            retries = 0
        }
        if type == "state" {
            state = message
            if let stop = pendingStop {
                let phase = message.optString("phase")
                let settled = phase == "unpaired"
                    || (!message.optBool("active") && !message.optBool("ready")
                        && (stop != "safeword" || message.optBool("safeWord")))
                if settled { pendingStop = nil }
                else { _ = send(Self.json(stop)); return }
            }
        }
        controller?.relayReceived(message)
        listener?.relayReceived(message)
    }

    private func startHeartbeat() {
        heartbeatWorkItem?.cancel()
        func tick() {
            guard connected else { return }
            if ProcessInfo.processInfo.systemUptime - lastLease > 2 {
                disconnect(reason: "Connection timed out · stopped")
                return
            }
            _ = send(Self.json("heartbeat"))
            let item = DispatchWorkItem { tick() }
            heartbeatWorkItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: item)
        }
        tick()
    }

    @discardableResult
    func send(_ message: JSONDictionary) -> Bool {
        guard connected, let task = socketTask else { return false }
        guard JSONSerialization.isValidJSONObject(message),
              let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8)
        else { return false }
        task.send(.string(text)) { [weak self] error in
            guard let self, error != nil else { return }
            DispatchQueue.main.async { self.disconnect(reason: "Connection lost · stopped") }
        }
        return true
    }

    func frame(_ value: JSONDictionary, priorityZero: Bool) {
        pendingFrame = value
        let now = ProcessInfo.processInfo.systemUptime
        if priorityZero || now - lastFrame >= 0.033 {
            flushFrame()
        } else {
            flushWorkItem?.cancel()
            let item = DispatchWorkItem { [weak self] in self?.flushFrame() }
            flushWorkItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + (0.033 - (now - lastFrame)), execute: item)
        }
    }

    private func flushFrame() {
        flushWorkItem?.cancel()
        guard let next = pendingFrame else { return }
        pendingFrame = nil
        lastFrame = ProcessInfo.processInfo.systemUptime
        _ = send(next)
    }

    func pause() {
        pendingFrame = nil
        flushWorkItem?.cancel()
        if pendingStop == nil { pendingStop = "pause" }
        _ = send(Self.json(pendingStop!))
    }

    func stop() {
        pendingFrame = nil
        flushWorkItem?.cancel()
        if pendingStop != "safeword" { pendingStop = "stop" }
        _ = send(Self.json(pendingStop!))
    }

    func safeWord() {
        pendingFrame = nil
        flushWorkItem?.cancel()
        pendingStop = "safeword"
        blockRetries(true)
        _ = send(Self.json("safeword"))
    }

    func clearSafeWordForReady() {
        pendingStop = nil
        blockRetries(false)
    }

    func shutdown() {
        wanted = false
        disconnect(reason: "Connection stopped")
    }

    func disconnect(reason: String) {
        generation += 1
        pendingFrame = nil
        flushWorkItem?.cancel()
        heartbeatWorkItem?.cancel()
        registrationTask?.cancel()
        registrationTask = nil
        if let task = socketTask {
            taskGeneration[ObjectIdentifier(task)] = nil
            task.cancel(with: .goingAway, reason: nil)
        }
        socketTask = nil
        report(false, reason)
        retry()
    }

    private func report(_ value: Bool, _ reason: String) {
        connected = value
        connectionReason = reason
        controller?.relayConnectionChanged(connected: value, reason: reason)
        listener?.relayConnectionChanged(connected: value, reason: reason)
    }

    nonisolated static func json(_ type: String, _ fields: JSONDictionary = [:]) -> JSONDictionary {
        var value = fields
        value["type"] = type
        return value
    }
}

extension RelayClient: OwnerRelay, PetRelay {}

extension RelayClient: URLSessionWebSocketDelegate {
    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        Task { @MainActor in
            guard let current = self.taskGeneration[ObjectIdentifier(webSocketTask)], current == self.generation else {
                webSocketTask.cancel()
                return
            }
            self.lastLease = ProcessInfo.processInfo.systemUptime
            self.reconnectWorkItem?.cancel()
            self.report(true, "Connected")
            if let stop = self.pendingStop { _ = self.send(Self.json(stop)) }
            self.startHeartbeat()
        }
    }

    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        Task { @MainActor in
            guard self.taskGeneration[ObjectIdentifier(webSocketTask)] != nil else { return }
            self.disconnect(reason: "Connection closed · stopped")
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard error != nil, let webSocketTask = task as? URLSessionWebSocketTask else { return }
        let rejected = (task.response as? HTTPURLResponse)?.statusCode == 401
        Task { @MainActor in
            guard self.taskGeneration[ObjectIdentifier(webSocketTask)] != nil else { return }
            if rejected { self.wanted = false }
            self.disconnect(reason: rejected ? "Phone identity rejected. Open relay settings." : "Connection lost · reconnecting…")
        }
    }
}
