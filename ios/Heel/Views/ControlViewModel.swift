import Foundation

/// Owner-side control session. Sends frames over the relay; no BLE or native bridge involved,
/// since only Pet drives a physical device. Mirrors the owner path of Android's MainActivity +
/// ControlRuntime, with Pet-only device discovery/recovery left out.
@MainActor
final class ControlViewModel: NSObject, ObservableObject, RelayListener {
    private let relay: OwnerRelay
    private let gate = SessionGate(pet: false)
    private let haptics: OwnerHaptics
    private let spellbook: SpellBook
    private let now: () -> Int64
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void
    private weak var touchModel: TouchModel?

    /// Where to go once the relay confirms the pair is gone: Pick after a role reset, otherwise
    /// back to pairing in the same role, as on Android.
    var onReturnToPick: (() -> Void)?
    var onUnpaired: (() -> Void)?

    @Published private(set) var relayOnline = false
    @Published private(set) var relayState: JSONDictionary = [:]
    @Published private(set) var deviceName: String?
    @Published private(set) var features: [TouchModel.Feature] = []
    @Published private(set) var hold = false
    @Published private(set) var touchVersion = 0
    @Published private(set) var displayValues: [Double] = []
    @Published private(set) var hapticsEnabled = HapticsPreferences.get()

    /// VoiceOver and Switch Control can't drag a paw, so Owner drives each feature with a slider
    /// and Start/Stop instead (Android's TalkBack "slider mode").
    @Published private(set) var sliderMode = false
    @Published private(set) var sliderLevels: [Double] = []
    @Published private(set) var sliderRunning: [Bool] = []

    @Published private(set) var spells: [SpellBook.Spell] = []
    @Published private(set) var isRecording = false
    @Published private(set) var pendingSpellDraft = false
    @Published private(set) var playingSpellName: String?
    @Published var spellError: String?
    @Published var resetError: String?

    private var resettingRole = false
    private var outgoingSequence: Int64 = 0
    private var lastSubmitted: [Double]?
    private var restoredEpoch = ""
    private var restoredSequence: Int64 = 0

    private var recording: SpellBook.Recording?
    private var recordingGeneration = 0
    private var draftRecording: SpellBook.Recording?
    private var draftFinishedAt: Int64 = 0
    private var playing: SpellBook.Spell?
    private var playbackStarted: Int64 = 0
    private var playbackEpoch = ""
    private var playbackGeneration = 0

    init(relay: OwnerRelay = RelayClient.shared, haptics: OwnerHaptics? = nil, spellbook: SpellBook = SpellBook(),
         now: @escaping () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) },
         schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
         }) {
        self.relay = relay
        self.haptics = haptics ?? OwnerHaptics(output: PhoneHapticsOutput())
        self.spellbook = spellbook
        self.now = now
        self.schedule = schedule
    }

    /// Becomes the relay's app-wide listener, so the session keeps running whichever screen is
    /// showing. SessionRuntime calls this when Owner's role is paired.
    func activate(onReturnToPick: @escaping () -> Void, onUnpaired: @escaping () -> Void) {
        self.onReturnToPick = onReturnToPick
        self.onUnpaired = onUnpaired
        resettingRole = false
        // setController replays the current state; clearing after it would leave Owner unable
        // to send until the relay's next state change.
        relayState = [:]
        relay.setController(self)
        spells = spellbook.all()
    }

    /// Leaving Owner's role ends its session; nothing keeps running for a role no one is using.
    func deactivate() {
        if gate.active() || gate.pending() { stopAll() } else { resetLocal() }
        haptics.stop()
        relay.setController(nil)
        onReturnToPick = nil
        onUnpaired = nil
        resettingRole = false
    }

    func screenDidAppear() {
        spells = spellbook.all()
    }

    /// The pad is gone, so nothing can release a held or touched level. Pause rather than leave
    /// the pet's device running at whatever was last sent.
    func screenDidDisappear() {
        stopForInterruption()
    }

    /// iOS suspends the app, and with it the relay socket, within seconds of backgrounding.
    /// Pausing now tells the pet's phone why, and a resume stays one tap away.
    func appDidEnterBackground() {
        stopForInterruption()
    }

    private func stopForInterruption() {
        haptics.stop()
        if gate.active() { pauseSession() } else { resetLocal() }
    }

    /// A pause the session can resume from, unlike stopAll.
    private func pauseSession() {
        gate.pause()
        resetLocal()
        relay.pause()
    }

    func setHapticsEnabled(_ enabled: Bool) {
        hapticsEnabled = enabled
        HapticsPreferences.set(enabled)
        updateHaptics()
    }

    /// Feels roughly what the pet's device is doing: the loudest point still held down,
    /// using its visual (continuous) level rather than the quantized output.
    private func updateHaptics() {
        guard hapticsEnabled, canControl, let touchModel else { haptics.update(0); return }
        let output = touchModel.targets()
        let visual = touchModel.visualTargets()
        var level = 0.0
        for index in output.indices where output[index] > 0 {
            level = max(level, visual[index])
        }
        haptics.update(level)
    }

    func bindTouchModel(_ model: TouchModel) {
        touchModel = model
    }

    // MARK: - RelayListener

    func relayConnectionChanged(connected: Bool, reason: String) {
        relayOnline = connected
        if !connected { resetLocal() }
    }

    func relayReceived(_ message: JSONDictionary) {
        switch message.optString("type") {
        case "state": handleState(message)
        case "applied":
            if gate.active(), message.optString("epoch") == gate.epoch {
                mirror(message)
            }
        default:
            break
        }
    }

    private func handleState(_ message: JSONDictionary) {
        relayState = message
        let paired = message.optString("phase") == "paired"
        guard paired, message.optString("role") == "owner" else {
            gate.stop()
            resetLocal()
            if !paired {
                if resettingRole {
                    resettingRole = false
                    onReturnToPick?()
                } else {
                    onUnpaired?()
                }
            }
            return
        }

        let incomingEpoch = message.optString("epoch")
        if message.optBool("safeWord") {
            let wasRunning = gate.active()
            if wasRunning || gate.pending() || gate.canRecover() || incomingEpoch != gate.epoch {
                gate.stop()
            }
            if wasRunning { resetLocal() }
        }

        let wasActive = gate.active()
        let oldEpoch = gate.epoch
        let accepted = gate.state(incoming: incomingEpoch, paired: true,
                                   online: message.optBool("online"), ready: message.optBool("ready"),
                                   active: message.optBool("active"), resumed: message.optBool("resumed"))
        guard accepted else {
            gate.stop(); resetLocal(); relay.stop(); return
        }
        if wasActive && !gate.active() { resetLocal() }
        if oldEpoch != gate.epoch { outgoingSequence = 0; lastSubmitted = nil }
        if message.optBool("active") { mirror(message) }
        if message.optBool("resumeAllowed") && !gate.canRecover() {
            relay.stop()
            return
        }
        if gate.active(), message.optBool("resumed") {
            restoreHeld(message.optDictionary("restore"), message.optDictionary("device"))
        }
        bindDevice(message.optDictionary("device"))
        updateHaptics()
    }

    /// After a reconnect, the relay replays the last acknowledged Hold level once so the pet's
    /// device doesn't sit at zero while the owner reopens the app. Reasserted as a fresh frame
    /// rather than trusted outright, since the relay's own reconnection still has to apply it.
    private func restoreHeld(_ restore: JSONDictionary?, _ device: JSONDictionary?) {
        // A held level came from a touch pad; sliders couldn't show or release it.
        guard let restore, let device, restore.optBool("hold"), !sliderMode,
              gate.canRecover(), restoredEpoch != gate.epoch, outgoingSequence == 0
        else { return }
        guard let sourceDevice = restore.optDictionary("device"),
              sourceDevice.optInt("id") == device.optInt("id"),
              (sourceDevice.optArray("features") ?? []).count == (device.optArray("features") ?? []).count
        else { return }
        let sourceValues = (restore.optArray("values") ?? []).compactMap { ($0 as? NSNumber)?.doubleValue }
        let featureList = (device.optArray("features") ?? []).compactMap { $0 as? JSONDictionary }
        guard sourceValues.count == featureList.count, (1...2).contains(sourceValues.count) else { return }
        var values: [Double] = []
        for (index, value) in sourceValues.enumerated() {
            guard value.isFinite, value >= 0, value <= 1 else { return }
            values.append(TouchModel.normalize(value, steps: featureList[index].optInt("steps")))
        }
        restoredEpoch = gate.epoch
        outgoingSequence += 1
        restoredSequence = outgoingSequence
        relay.frame(RelayClient.json("frame", [
            "epoch": restoredEpoch, "seq": restoredSequence, "values": values, "hold": true,
        ]), priorityZero: true)
    }

    private func bindDevice(_ device: JSONDictionary?) {
        guard let device else {
            if deviceName != nil { deviceName = nil; features = []; sliderLevels = []; sliderRunning = [] }
            return
        }
        let name = device.optString("name")
        let rawFeatures = (device.optArray("features") ?? []).compactMap { $0 as? JSONDictionary }
        let parsed = rawFeatures.map {
            TouchModel.Feature(scalarIndex: $0.optInt("index"), label: $0.optString("label"), stepCount: $0.optInt("steps"))
        }
        guard name != deviceName || parsed != features else { return }
        deviceName = name
        features = parsed
        sliderLevels = Array(repeating: 0, count: parsed.count)
        sliderRunning = Array(repeating: false, count: parsed.count)
    }

    private func mirror(_ message: JSONDictionary) {
        guard let touchModel else { return }
        let values = (message.optArray("values") ?? []).compactMap { ($0 as? NSNumber)?.doubleValue }
        guard values.count == features.count else { return }
        try? touchModel.setVisualTargets(values)
        hold = message.optBool("hold")
        touchModel.setHold(hold)
        touchVersion += 1
        displayValues = touchModel.visualTargets()
        updateHaptics()
    }

    // MARK: - Session controls

    var canControl: Bool {
        relayOnline && relayState.optString("phase") == "paired" && relayState.optString("role") == "owner"
            && gate.active() && !features.isEmpty
    }

    var sessionActive: Bool { gate.active() }
    var sessionPending: Bool { gate.pending() }

    var statusText: String {
        guard relayOnline else { return "Reconnecting…" }
        guard relayState.optBool("online") else { return "Waiting for your pet…" }
        if gate.active() { return "Together ♡" }
        if relayState.optBool("paused") { return "Paused · resume when you like" }
        if relayState.optBool("ready") { return "Your pet is ready" }
        return "Ready when you are"
    }

    /// Pause leaves the session resumable; the button must say so rather than "Start".
    private var resumable: Bool {
        relayState.optBool("resumeAllowed") && !relayState.optBool("safeWord") && gate.canRecover()
    }

    var sessionActionTitle: String {
        !relayOnline ? "Reconnect" : gate.active() ? "Together ♡" : gate.pending() ? "One moment…"
            : resumable ? "Resume ♡" : "Start"
    }

    /// A session is live, starting, or one tap from starting, so the phone must not lock under it.
    /// Unlike Android's wake lock this ignores a merely resumable session: that clause exists to
    /// reconnect in the background, which iOS can't do, and here it would pin the screen on forever
    /// after a pause.
    /// What the Lock Screen activity says; nil while there is no session worth showing.
    var activityPhase: SessionPhase? {
        if gate.active() { return .together }
        if relayState.optBool("paused") { return .paused }
        return keepsScreenAwake ? .waiting : nil
    }

    var keepsScreenAwake: Bool {
        relayOnline && (gate.active() || gate.pending() || relayState.optBool("ready"))
    }

    var sessionActionEnabled: Bool {
        guard relayOnline else { return true }
        let online = relayState.optBool("online")
        return online && !gate.active() && gate.mayRequest() && (resumable || relayState.optBool("ready"))
    }

    func action() {
        guard relayOnline else { relay.connect(enrollment: nil); return }
        guard !gate.active(), gate.mayRequest() else { return }
        if resumable {
            relay.send(RelayClient.json("resume", ["epoch": gate.epoch]))
            return
        }
        if relayState.optBool("ready") {
            gate.requestStart()
            relay.send(RelayClient.json("start", ["epoch": gate.epoch]))
        }
    }

    func stopAll() {
        gate.stop()
        resetLocal()
        relay.stop()
    }

    /// Ends the pair on the relay too, so Pick isn't reached with a pair still standing.
    /// Navigation happens when the relay confirms "unpaired" (see handleState).
    func unpairAndReset() {
        guard relayOnline else {
            resetError = "Reconnect first so Heel can unpair before changing roles."
            return
        }
        stopAll()
        resettingRole = true
        if !relay.send(RelayClient.json("unpair")) {
            resettingRole = false
            resetError = "Reconnect to reset the role."
        }
    }

    func setHold(_ enabled: Bool) {
        hold = enabled
        touchModel?.setHold(enabled)
        touchVersion += 1
        if canControl, let targets = touchModel?.targets() {
            submitFrame(targets, force: true)
        }
        updateHaptics()
    }

    func touchTargetsChanged(_ targets: [Double]) {
        guard canControl else { return }
        submitFrame(targets)
    }

    func touchStateChanged() {
        if playing != nil, let touchModel, touchModel.activeTouchCount() > 0 { stopPlayback() }
        captureGesture()
        touchVersion += 1
        displayValues = touchModel?.visualTargets() ?? []
        updateHaptics()
    }

    func touchCancelled() {
        stopAll()
    }

    // MARK: - Slider mode

    /// Smallest VoiceOver-friendly increment: one device step, but never finer than 5%.
    static func sliderStep(for feature: TouchModel.Feature) -> Double {
        feature.stepCount > 0 ? max(1 / Double(feature.stepCount), 0.05) : 0.05
    }

    /// Switching input mode pauses a running session rather than carrying output across it.
    func setSliderMode(_ enabled: Bool) {
        guard enabled != sliderMode else { return }
        if gate.active() { pauseSession() }
        sliderMode = enabled
        resetLocal()
    }

    func setSliderLevel(_ index: Int, _ value: Double) {
        guard sliderMode, canControl, features.indices.contains(index) else { return }
        if playing != nil { stopPlayback() }
        sliderLevels[index] = TouchModel.normalize(value, steps: features[index].stepCount)
        if sliderRunning[index] { submitSliders() }
    }

    func startSlider(_ index: Int) {
        guard sliderMode, canControl, features.indices.contains(index) else { return }
        if playing != nil { stopPlayback() }
        sliderRunning[index] = true
        submitSliders()
    }

    func stopSlider(_ index: Int) {
        guard sliderMode, canControl, features.indices.contains(index) else { return }
        if playing != nil { stopPlayback() }
        sliderRunning[index] = false
        submitSliders()
    }

    /// Only a running feature sends its level; a stopped one sends zero.
    private func submitSliders() {
        let targets = features.indices.map { sliderRunning[$0] ? sliderLevels[$0] : 0 }
        // A rejected frame must never restore targets after Stop.
        guard submitFrame(targets) else { return }
        try? touchModel?.setVisualTargets(targets)
        touchVersion += 1
        displayValues = touchModel?.visualTargets() ?? targets
        captureGesture()
        updateHaptics()
    }

    // MARK: - Spellbook

    var canPerformSpell: Bool { canControl }
    var isPlayingSpell: Bool { playingSpellName != nil }

    func startRecording() {
        guard canPerformSpell, recording == nil, playing == nil else { return }
        resetLocal()
        guard let draft = try? SpellBook.Recording(channels: features.count, now: now()) else { return }
        recording = draft
        isRecording = true
        recordingGeneration += 1
        let generation = recordingGeneration
        schedule(Double(SpellBook.maxDuration) / 1000) { [weak self] in
            guard let self, generation == self.recordingGeneration else { return }
            self.finishRecording()
        }
    }

    func finishRecording() {
        guard let draft = recording else { return }
        let finishedAt = now()
        recording = nil
        isRecording = false
        recordingGeneration += 1
        resetLocal()
        guard draft.hasMovement() else { spellError = "Move a paw to make a recording"; return }
        draftRecording = draft
        draftFinishedAt = finishedAt
        pendingSpellDraft = true
    }

    func discardDraft() {
        draftRecording = nil
        pendingSpellDraft = false
    }

    func saveDraft(name: String, sticker: Int) {
        guard let draft = draftRecording else { return }
        do {
            let spell = try draft.finish(now: draftFinishedAt, name: name, sticker: sticker)
            try spellbook.save(spell)
            draftRecording = nil
            pendingSpellDraft = false
            spells = spellbook.all()
        } catch {
            spellError = (error as? SpellBook.SpellBookError)?.errorDescription ?? "Could not save your Spellbook. Please try again."
        }
    }

    func deleteSpell(_ id: String) {
        do {
            try spellbook.delete(id: id)
            spells = spellbook.all()
        } catch {
            spellError = (error as? SpellBook.SpellBookError)?.errorDescription ?? "Could not save your Spellbook. Please try again."
        }
    }

    func startPlayback(_ spell: SpellBook.Spell) {
        guard canPerformSpell, spell.channels == features.count else {
            spellError = "Choose a matching device and start a session first"
            return
        }
        resetLocal()
        playing = spell
        playingSpellName = spell.name
        playbackStarted = now()
        playbackEpoch = gate.epoch
        playbackGeneration += 1
        playbackTick(playbackGeneration)
    }

    func stopPlayback() {
        guard playing != nil else { return }
        resetLocal()
    }

    private func captureGesture() {
        guard let recording, let touchModel else { return }
        var touching = 0
        for point in 0..<touchModel.size
        where touchModel.isPointActive(point) || (sliderMode && sliderRunning.indices.contains(point) && sliderRunning[point]) {
            touching |= 1 << point
        }
        let accepted = (try? recording.add(now: now(), values: touchModel.visualTargets(), touching: touching)) ?? false
        if !accepted { finishRecording() }
    }

    private func playbackTick(_ generation: Int) {
        guard generation == playbackGeneration, let spell = playing, let touchModel,
              canPerformSpell, playbackEpoch == gate.epoch
        else { stopPlayback(); return }
        let elapsed = now() - playbackStarted
        guard elapsed < spell.duration else { stopPlayback(); return }
        guard (try? touchModel.setVisualTargets(spell.at(elapsed))) != nil else { stopPlayback(); return }
        touchVersion += 1
        displayValues = touchModel.visualTargets()
        guard submitFrame(touchModel.targets()) else { stopPlayback(); return }
        updateHaptics()
        schedule(1.0 / 60.0) { [weak self] in self?.playbackTick(generation) }
    }

    @discardableResult
    private func submitFrame(_ targets: [Double], force: Bool = false) -> Bool {
        guard canControl, targets.count == features.count else { return false }
        if !force, targets == lastSubmitted { return true }
        var zero = false
        var values: [Double] = []
        for (index, value) in targets.enumerated() {
            let normalized = TouchModel.normalize(value, steps: features[index].stepCount)
            if normalized == 0, let last = lastSubmitted, index < last.count, last[index] > 0 { zero = true }
            values.append(normalized)
        }
        outgoingSequence += 1
        relay.frame(RelayClient.json("frame", [
            "epoch": gate.epoch, "seq": outgoingSequence, "values": values, "hold": hold,
        ]), priorityZero: zero)
        lastSubmitted = targets
        return true
    }

    private func resetLocal() {
        touchModel?.clear()
        lastSubmitted = nil
        hold = false
        sliderLevels = Array(repeating: 0, count: features.count)
        sliderRunning = Array(repeating: false, count: features.count)
        touchVersion += 1
        displayValues = Array(repeating: 0, count: features.count)
        haptics.stop()
        recording = nil
        isRecording = false
        recordingGeneration += 1
        playing = nil
        playingSpellName = nil
        playbackGeneration += 1
    }
}
