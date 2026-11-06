package dev.prototype.lovense;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import android.bluetooth.BluetoothAdapter;
import com.nonpolynomial.btleplug.android.impl.BleRecovery;

/** Owns device IO and session authority independently of any Activity or View. Main thread only. */
final class ControlRuntime implements RelayClient.Listener {
    interface Observer { void onNative(JSONObject event); }
    interface Engine { String initialize(); String submit(String command); String drain(); }
    private static ControlRuntime instance;
    static synchronized ControlRuntime get(Context context) {
        if (instance == null) instance = new ControlRuntime(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicLong requests = new AtomicLong();
    final RelayClient relay;
    SessionGate gate;
    Engine engine = new Engine() {
        public String initialize() { return NativeBridge.initialize(); }
        public String submit(String command) { return NativeBridge.submit(command); }
        public String drain() { return NativeBridge.drain(); }
    };
    private Observer observer;
    boolean pet, initialized, ready, stopping, online, safeWord, running;
    JSONArray devices = new JSONArray();
    JSONObject state = new JSONObject(), selected;
    double[] accepted = new double[0];
    boolean hold;
    boolean recoveringDevice;
    private JSONObject selectedSnapshot;
    private boolean recoveryScanning, recoveredCandidate, readyAfterRecovery;
    long outgoingSequence;
    private String advertised = "", recoveryRequest = "";
    private String restoredEpoch = "";
    private String pendingReadyEpoch = "";
    private final Runnable readyTimeout = () -> failPendingReady();
    private long restoredSequence;
    private final LinkedHashMap<Long, JSONObject> frames = new LinkedHashMap<>();
    private long acceptedSequence;
    private Runnable notificationChanged;
    private ControlRuntime(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("heel-session", Context.MODE_PRIVATE);
        safeWord = prefs.getBoolean("safeWord", false);
        pet = "pet".equals(RolePreferences.get(context)); gate = new SessionGate(pet);
        relay = RelayClient.get(context); relay.controller(this); relay.blockRetries(safeWord);
    }
    void configure(boolean asPet) {
        if (pet != asPet) {
            cancelPendingReady();
            cancelDeviceRecovery(); selectedSnapshot = null; BleRecovery.block();
            if (state.optString("phase").equals("paired")) pause(false);
            if (initialized) submit(RelayClient.json("suspend").toString());
            initialized = ready = stopping = false; pet = asPet; gate = new SessionGate(pet); selected = null; devices = new JSONArray();
        }
        safeWord = pet && prefs.getBoolean("safeWord",false); relay.blockRetries(safeWord);
    }
    void observe(Observer value) { observer = value; }
    void detach(Observer value) { if (observer == value) observer = null; }
    void notificationChanged(Runnable value) { notificationChanged = value; }
    void start() { if (running) return; running = true; main.post(poll); if (!safeWord) relay.connect(null); }
    void stop() { running = false; cancelPendingReady(); cancelDeviceRecovery(); BleRecovery.block(); main.removeCallbacks(poll); main.removeCallbacks(stopScan); gate.stop(); stopNative(); relay.shutdown(); }
    private void notifyUi() { if (notificationChanged != null) notificationChanged.run(); }
    boolean pairedPet() { return pet && online && state.optString("phase").equals("paired") && state.optString("role").equals("pet"); }
    boolean available() { return pairedPet() && initialized && ready && !stopping && selected != null && !recoveringDevice; }
    String initialize() {
        if (!pairedPet()) return "Pair this phone as Pet first.";
        if (initialized) return "";
        String error = engine.initialize(); if ("".equals(error)) initialized = true; return error;
    }
    String submit(String text) {
        try {
            String type = new JSONObject(text).getString("type");
            if (!initialized) return "Device engine is not ready.";
            if (!pairedPet() && !type.equals("stop") && !type.equals("suspend") && !(type.equals("scan") && !new JSONObject(text).optBoolean("enabled"))) return "Pet connection is required.";
            if (type.equals("vibration_frame") && (safeWord || !gate.active())) return "Control is stopped.";
            if (type.equals("scan") && new JSONObject(text).optBoolean("enabled") && safeWord && !readyAfterRecovery) return "Choose Ready again before reconnecting.";
            if (type.equals("scan")) { main.removeCallbacks(stopScan); if (new JSONObject(text).optBoolean("enabled")) main.postDelayed(stopScan,15000); }
            return engine.submit(text);
        } catch (JSONException | RuntimeException | LinkageError error) { return error.toString(); }
    }
    void select(JSONObject device) {
        String previous = selected == null ? "null" : selected.toString();
        String next = device == null ? "null" : device.toString();
        if (!previous.equals(next)) {
            cancelPendingReady();
            cancelDeviceRecovery(); selectedSnapshot = null;
            gate.stop(); stopNative(); selected = device; accepted = new double[device == null ? 0 : device.optJSONArray("features").length()];
            if (selected != null) for (int i=0;i<devices.length();i++) {
                JSONObject candidate = devices.optJSONObject(i);
                if (candidate != null && candidate.optInt("id",-1) == selected.optInt("id",-2)) selectedSnapshot = candidate;
            }
        }
        advertise();
    }
    private void advertise() {
        if (!pairedPet()) return;
        Object device = initialized && (ready || recoveringDevice) && selected != null ? selected : JSONObject.NULL;
        String signature = device.toString();
        if (!signature.equals(advertised)) { advertised = signature; relay.send(RelayClient.json("device", "device", device)); }
    }
    void pause(boolean temporary) {
        cancelPendingReady();
        if (temporary) gate.pause(); else gate.stop();
        stopNative(); if (temporary) relay.pause(); else relay.stop(); notifyUi();
    }
    void safeWord() {
        cancelPendingReady();
        safeWord = true; prefs.edit().putBoolean("safeWord", true).commit();
        readyAfterRecovery = false; stopRecoveryScan(); BleRecovery.block();
        gate.stop(); stopNative(); relay.safeWord(); notifyUi();
    }
    void action() {
        if (pet && safeWord && recoveringDevice) {
            readyAfterRecovery = true; BleRecovery.target(selectedSnapshot.optString("address"));
            relay.blockRetries(false); if (!online) relay.connect(null); scheduleRecovery(0); notifyUi(); return;
        }
        if (!online) { relay.blockRetries(false); relay.connect(null); return; }
        if (pet && recoveringDevice) return;
        if (gate.active() || !gate.mayRequest()) return;
        if (state.optBoolean("resumeAllowed") && !state.optBoolean("safeWord") && gate.canRecover()) {
            relay.send(RelayClient.json("resume", "epoch", gate.epoch())); return;
        }
        if (pet) {
            if (!available()) return;
            readyAfterRecovery = false;
            // Keep the persisted Safe Word latch until the relay acknowledges this request.
            relay.clearSafeWordForReady();
            gate.requestReady(); pendingReadyEpoch = gate.epoch();
            main.removeCallbacks(readyTimeout); main.postDelayed(readyTimeout,5000);
            if (!relay.send(RelayClient.json("ready", "epoch", pendingReadyEpoch))) failPendingReady();
        } else if (state.optBoolean("ready")) { gate.requestStart(); relay.send(RelayClient.json("start", "epoch", gate.epoch())); }
        notifyUi();
    }
    private void stopNative() {
        frames.clear(); acceptedSequence = 0; Arrays.fill(accepted, 0); hold = false;
        if (initialized) { stopping = true; String error = submit(RelayClient.json("stop").toString()); if (!"".equals(error)) { ready = false; stopping = false; } }
    }
    private void cancelPendingReady() { pendingReadyEpoch = ""; main.removeCallbacks(readyTimeout); }
    private void failPendingReady() {
        if (pendingReadyEpoch.isEmpty()) return;
        cancelPendingReady(); gate.stop(); stopNative();
        if (safeWord) relay.safeWord(); else relay.stop();
        notifyUi();
    }
    @Override public void onConnection(boolean connected, String reason) {
        online = connected;
        if (!connected) { cancelPendingReady(); gate.disconnected(); advertised = ""; recoveryRequest = ""; stopNative(); }
        notifyUi();
    }
    @Override public void onRelay(JSONObject message) {
        try {
            switch (message.optString("type")) {
                case "state": {
                    state = message;
                    boolean paired = message.optString("phase").equals("paired");
                    if (!paired || !message.optString("role").equals(pet ? "pet" : "owner")) {
                        cancelPendingReady();
                        cancelDeviceRecovery(); selectedSnapshot = null; BleRecovery.block(); gate.stop(); stopNative(); if (!paired) { selected = null; relay.blockRetries(false); } return;
                    }
                    String incomingEpoch = message.optString("epoch");
                    boolean awaitingReady = pet && !pendingReadyEpoch.isEmpty();
                    boolean readyAck = awaitingReady && !incomingEpoch.equals(pendingReadyEpoch)
                        && message.optBoolean("online") && message.optBoolean("ready") && !message.optBoolean("active")
                        && !message.optBoolean("safeWord") && message.optBoolean("deviceOnline",true) && available();
                    boolean priorSafeState = awaitingReady && incomingEpoch.equals(pendingReadyEpoch)
                        && !message.optBoolean("ready") && !message.optBoolean("active");
                    if (awaitingReady && !readyAck && !priorSafeState) cancelPendingReady();
                    if (message.optBoolean("safeWord") && !priorSafeState) {
                        boolean wasRunning = gate.active();
                        // Repeated status for an already-stopped epoch must not create
                        // a new wait-for-stop barrier that the same epoch cannot satisfy.
                        if (wasRunning || gate.pending() || gate.canRecover() || !incomingEpoch.equals(gate.epoch())) gate.stop();
                        if (wasRunning) stopNative();
                        if (pet) { safeWord = true; prefs.edit().putBoolean("safeWord", true).commit(); if (!readyAfterRecovery) { stopRecoveryScan(); BleRecovery.block(); relay.blockRetries(true); } }
                    }
                    boolean wasActive = gate.active(); String oldEpoch = gate.epoch();
                    if (safeWord && message.optBoolean("active") || !gate.state(message.optString("epoch"), true, message.optBoolean("online"), message.optBoolean("ready"), message.optBoolean("active"), message.optBoolean("resumed"))) {
                        gate.stop(); stopNative(); if (safeWord) relay.safeWord(); else relay.stop(); return;
                    }
                    if (wasActive && !gate.active()) stopNative();
                    if (readyAck && gate.readyForEpoch(incomingEpoch)) {
                        cancelPendingReady(); safeWord = false;
                        prefs.edit().putBoolean("safeWord",false).commit(); BleRecovery.discover();
                    }
                    if (!oldEpoch.equals(gate.epoch())) { frames.clear(); acceptedSequence = 0; outgoingSequence = 0; accepted = new double[selected == null ? 0 : selected.getJSONArray("features").length()]; hold = false; }
                    if (!pet && message.optBoolean("active")) mirror(message);
                    if (message.optBoolean("resumeAllowed") && !gate.canRecover()) { relay.stop(); return; }
                    if (!pet && gate.active() && message.optBoolean("resumed")) restoreHeld(message.optJSONObject("restore"), message.optJSONObject("device"));
                    if (pet) { advertise(); reportDeviceLink(); if (recoveringDevice) scheduleRecovery(0); }
                    tryRecover(); break;
                }
                case "lease":
                    if (gate.active() && (!message.optBoolean("active") || !message.optBoolean("peerFresh") || !message.optString("epoch").equals(gate.epoch()))) {
                        gate.disconnected(); stopNative(); relay.disconnect("Connection interrupted · reconnecting…");
                    }
                    tryRecover(); break;
                case "frame": apply(message); break;
                case "applied": if (!pet && gate.active() && message.optString("epoch").equals(gate.epoch()) && message.optLong("seq") > acceptedSequence && message.optLong("seq") <= outgoingSequence) { mirror(message); acceptedSequence = message.optLong("seq"); } break;
                case "error": recoveryRequest = ""; failPendingReady(); break;
            }
        } catch (JSONException | RuntimeException error) { pause(false); }
        notifyUi();
    }
    private void tryRecover() {
        if (pet && available() && state.optBoolean("deviceOnline",true) && !safeWord && gate.canRecover() && !gate.active() && state.optBoolean("online") && state.optBoolean("resumeAllowed") && !state.optBoolean("paused") && !state.optBoolean("safeWord") && !gate.epoch().equals(recoveryRequest)) {
            recoveryRequest = gate.epoch(); relay.send(RelayClient.json("resume", "epoch", gate.epoch(), "automatic", true));
        }
    }
    private void mirror(JSONObject message) throws JSONException {
        JSONArray values = message.optJSONArray("values"); if (values == null) return;
        accepted = new double[values.length()]; for (int i=0;i<values.length();i++) accepted[i] = values.getDouble(i);
        hold = message.optBoolean("hold");
    }
    private void restoreHeld(JSONObject restore, JSONObject device) throws JSONException {
        if (restore == null || device == null || !restore.optBoolean("hold") || safeWord || !gate.canRecover() || restoredEpoch.equals(gate.epoch()) || outgoingSequence != 0) return;
        JSONObject sourceDevice = restore.optJSONObject("device");
        if (sourceDevice == null || sourceDevice.getInt("id") != device.getInt("id") || !sourceDevice.getJSONArray("features").toString().equals(device.getJSONArray("features").toString())) return;
        JSONArray source = restore.getJSONArray("values"), features = device.getJSONArray("features"), values = new JSONArray();
        if (source.length() != features.length() || source.length() < 1 || source.length() > 2) throw new JSONException("Invalid Hold recovery");
        for (int i=0;i<source.length();i++) {
            double value = source.getDouble(i); if (!Double.isFinite(value) || value < 0 || value > 1) throw new JSONException("Invalid held level");
            values.put(TouchModel.normalize(value,features.getJSONObject(i).getInt("steps")));
        }
        restoredEpoch = gate.epoch(); restoredSequence = ++outgoingSequence;
        relay.frame(RelayClient.json("frame","epoch",restoredEpoch,"seq",restoredSequence,"values",values,"hold",true),true);
    }
    boolean restoredAcknowledgment(JSONObject message) {
        return gate.active() && message.optString("epoch").equals(restoredEpoch) && message.optLong("seq") == restoredSequence && outgoingSequence == restoredSequence;
    }
    private void apply(JSONObject message) throws JSONException {
        if (!pet || safeWord || !gate.acceptFrame(message.optString("epoch"), message.optLong("seq"))) return;
        if (!available()) { if (recoveringDevice) reportDeviceLink(); else pause(true); return; }
        JSONArray input = message.getJSONArray("values"), features = selected.getJSONArray("features"), values = new JSONArray();
        if (input.length() != features.length()) throw new JSONException("Feature mismatch");
        for (int i=0;i<input.length();i++) {
            double value = input.getDouble(i); if (!Double.isFinite(value) || value < 0 || value > 1) throw new JSONException("Invalid level");
            value = TouchModel.normalize(value, features.getJSONObject(i).getInt("steps"));
            input.put(i, value); values.put(new JSONObject().put("index", features.getJSONObject(i).getInt("index")).put("value", value));
        }
        long request = requests.incrementAndGet();
        String error = submit(RelayClient.json("vibration_frame", "device", selected.getInt("id"), "request", request, "values", values).toString());
        if (!"".equals(error)) { if (selectedSnapshot != null && BleRecovery.disconnected(selectedSnapshot.optString("address"))) beginDeviceRecovery(); else pause(false); return; }
        frames.put(request, new JSONObject(message.toString()));
        while (frames.size() > 64) frames.remove(frames.keySet().iterator().next());
    }
    void nativeEvent(JSONObject event) throws JSONException {
        switch (event.optString("type")) {
            case "ready": ready = event.optBoolean("active"); if (!ready && gate.active()) pause(false); break;
            case "stopped":
                if (!stopping && gate.active()) { gate.pause(); relay.pause(); }
                stopping = false; ready = event.optBoolean("active") && event.optBoolean("confirmed") && selectedStopConfirmed(event);
                if (recoveringDevice && recoveredCandidate && event.optBoolean("confirmed") && ready) finishDeviceRecovery();
                else if (recoveringDevice) { recoveredCandidate = false; scheduleRecovery(1000); }
                break;
            case "devices": {
                JSONArray previous = devices; devices = event.getJSONArray("devices");
                if (selected != null) {
                    boolean found = false;
                    for (int i=0;i<devices.length();i++) if (devices.getJSONObject(i).optInt("id", -1) == selected.optInt("id", -2)) {
                        found = true;
                        for (int j=0;j<previous.length();j++) if (previous.getJSONObject(j).optInt("id",-3) == selected.optInt("id",-2) && !previous.getJSONObject(j).optJSONObject("messages").toString().equals(devices.getJSONObject(i).optJSONObject("messages").toString())) found = false;
                    }
                    if (!found && !recoveringDevice) beginDeviceRecovery();
                    if (recoveringDevice) {
                        for (int i=0;i<devices.length();i++) {
                            JSONObject candidate = devices.getJSONObject(i);
                            if (selectedSnapshot != null && candidate.optString("identity").equals(selectedSnapshot.optString("identity")) && candidate.optInt("id",-1) == selectedSnapshot.optInt("id",-2)
                                    && !candidate.optJSONObject("messages").toString().equals(selectedSnapshot.optJSONObject("messages").toString())) {
                                cancelDeviceRecovery(); gate.stop(); stopNative(); selected = null; selectedSnapshot = null; relay.stop(); break;
                            }
                            if (sameReturningDevice(candidate) && !recoveredCandidate && (!safeWord || readyAfterRecovery)) {
                                recoveredCandidate = true; selectedSnapshot = candidate; stopRecoveryScan();
                                // Confirm zero on the reidentified hardware before allowing a fresh Hold frame.
                                stopNative(); break;
                            }
                        }
                    }
                }
                break;
            }
            case "vibration_applied": {
                JSONObject frame = frames.remove(event.optLong("request"));
                if (frame != null && !safeWord && !stopping && gate.active() && frame.optString("epoch").equals(gate.epoch()) && frame.optLong("seq") > acceptedSequence) {
                    JSONArray values = frame.getJSONArray("values"); accepted = new double[values.length()];
                    for (int i=0;i<values.length();i++) accepted[i] = values.getDouble(i);
                    acceptedSequence = frame.getLong("seq"); hold = frame.optBoolean("hold");
                    relay.send(RelayClient.json("applied", "epoch", gate.epoch(), "seq", acceptedSequence, "values", values));
                }
                break;
            }
            case "error":
                if (selectedSnapshot != null && BleRecovery.disconnected(selectedSnapshot.optString("address"))) beginDeviceRecovery();
                if (recoveringDevice) { recoveredCandidate = false; scheduleRecovery(1000); } else { ready = false; pause(false); }
                break;
            case "fatal": cancelDeviceRecovery(); ready = false; pause(false); break;
        }
        advertise(); tryRecover(); if (observer != null) observer.onNative(event); notifyUi();
    }
    private boolean selectedStopConfirmed(JSONObject event) {
        if (selected == null) return true;
        JSONArray stopped = event.optJSONArray("devices");
        if (stopped != null) for (int i=0;i<stopped.length();i++)
            if (stopped.optInt(i,-1) == selected.optInt("id",-2)) return true;
        return false;
    }
    void bluetoothOff() { if (selected != null) beginDeviceRecovery(); stopRecoveryScan(); ready = false; advertise(); notifyUi(); }
    void bluetoothOn() { if (recoveringDevice) scheduleRecovery(0); }
    void manualScan() { if (!safeWord && !recoveringDevice) BleRecovery.discover(); }
    JSONArray displayDevices() {
        if (!recoveringDevice || selectedSnapshot == null) return devices;
        JSONArray result = new JSONArray();
        for (int i=0;i<devices.length();i++) if (devices.optJSONObject(i).optInt("id",-1) != selectedSnapshot.optInt("id",-2)) result.put(devices.optJSONObject(i));
        result.put(selectedSnapshot); return result;
    }
    private boolean sameReturningDevice(JSONObject candidate) {
        return selectedSnapshot != null && !selectedSnapshot.optString("identity").isEmpty()
            && !BleRecovery.disconnected(candidate.optString("address"))
            && candidate.optString("identity").equals(selectedSnapshot.optString("identity"))
            && candidate.optInt("id",-1) == selectedSnapshot.optInt("id",-2)
            && candidate.optJSONObject("messages") != null && candidate.optJSONObject("messages").toString().equals(selectedSnapshot.optJSONObject("messages").toString());
    }
    private void beginDeviceRecovery() {
        if (recoveringDevice || selected == null) return;
        cancelPendingReady();
        if (selectedSnapshot == null || selectedSnapshot.optString("identity").isEmpty() || BleRecovery.address(selectedSnapshot.optString("address")).isEmpty()) {
            gate.stop(); stopNative(); selected = null; relay.stop(); return;
        }
        recoveringDevice = true; recoveredCandidate = false; recoveryRequest = "";
        if (!safeWord || readyAfterRecovery) BleRecovery.target(selectedSnapshot.optString("address")); else BleRecovery.block();
        gate.disconnected(); stopNative(); stopRecoveryScan();
        if (safeWord && !readyAfterRecovery) BleRecovery.block();
        reportDeviceLink(); scheduleRecovery(250); notifyUi();
    }
    private void reportDeviceLink() {
        if (pairedPet() && selected != null && state.optBoolean("deviceOnline",true) == recoveringDevice)
            relay.send(RelayClient.json("device_link","id",selected.optInt("id"),"connected",!recoveringDevice));
    }
    private void finishDeviceRecovery() {
        recoveringDevice = recoveredCandidate = false; stopRecoveryScan(); reportDeviceLink();
        if (readyAfterRecovery) action();
        notifyUi();
    }
    private void cancelDeviceRecovery() { if (recoveringDevice || recoveryScanning) stopRecoveryScan(); recoveringDevice = recoveredCandidate = readyAfterRecovery = false; }
    private void stopRecoveryScan() {
        main.removeCallbacks(retryDevice); main.removeCallbacks(endRecoveryScan); main.removeCallbacks(stopScan);
        if (initialized) submit(RelayClient.json("scan","enabled",false).toString());
        recoveryScanning = false;
    }
    private void scheduleRecovery(long delay) {
        if (!recoveringDevice || recoveredCandidate || recoveryScanning || safeWord && !readyAfterRecovery) return;
        main.removeCallbacks(retryDevice); main.postDelayed(retryDevice,delay);
    }
    private final Runnable endRecoveryScan = () -> { stopRecoveryScan(); scheduleRecovery(2000); };
    private final Runnable retryDevice = () -> {
        if (!recoveringDevice || safeWord && !readyAfterRecovery) return;
        if (!pairedPet() || stopping) { scheduleRecovery(1000); return; }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) { scheduleRecovery(2000); return; }
            BleRecovery.target(selectedSnapshot.optString("address"));
            if (!ready) submit(RelayClient.json("resume").toString());
            String error = submit(RelayClient.json("scan","enabled",true).toString());
            if ("".equals(error)) { recoveryScanning = true; main.postDelayed(endRecoveryScan,15000); }
            else scheduleRecovery(2000);
        } catch (RuntimeException error) { scheduleRecovery(2000); }
    };
    private final Runnable stopScan = () -> { if (initialized) submit(RelayClient.json("scan","enabled",false).toString()); };
    private final Runnable poll = new Runnable() { public void run() {
        if (!running) return;
        if (initialized) try {
            if (selectedSnapshot != null && BleRecovery.disconnected(selectedSnapshot.optString("address"))) beginDeviceRecovery();
            JSONArray events = new JSONArray(engine.drain());
            for (int i=0;i<events.length();i++) nativeEvent(events.getJSONObject(i));
        } catch (Exception | LinkageError error) { ready = false; pause(false); }
        main.postDelayed(this, initialized ? 50 : 500);
    }};
}
