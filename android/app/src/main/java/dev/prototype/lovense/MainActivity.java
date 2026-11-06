package dev.prototype.lovense;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.LocationManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import com.nonpolynomial.btleplug.android.impl.BleDiagnostics;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** View layer. The foreground service owns relay and device IO while this Activity is away. */
public final class MainActivity extends Activity implements RelayClient.Listener {
    interface DeviceBridge { String initialize(); String submit(String command); String drain(); }
    private DeviceBridge engine = new DeviceBridge() {
        public String initialize() { return NativeBridge.initialize(); }
        public String submit(String command) { return NativeBridge.submit(command); }
        public String drain() { return NativeBridge.drain(); }
    };
    private static final AtomicLong REQUESTS = new AtomicLong();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, DeviceInfo> devices = new LinkedHashMap<>();
    // A local zero is not proof that a pending zero reached the device.
    private final Set<Integer> possiblyRunning = new HashSet<>();
    private final ArrayList<Integer> deviceOptions = new ArrayList<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final ArrayList<TextView> pointValues = new ArrayList<>(), pointStates = new ArrayList<>(), sliderValues = new ArrayList<>();
    private final ArrayList<SeekBar> sliders = new ArrayList<>();
    private final ArrayList<Button> sliderStarts = new ArrayList<>(), sliderStops = new ArrayList<>();
    private Spinner devicePicker, bankPicker;
    private TextView status, capability, hint, touchCount, noControls, logView;
    private LinearLayout readouts, sliderPanel;
    private Button scan, stop, batteryRead, spellbookButton, recordingButton;
    private SpellBook spellbook;
    private SpellBook.Recording recording;
    private SpellBook.Spell playing;
    private long playbackStarted;
    private String playbackEpoch = "", playbackDevice = "", uiError = "";
    private boolean manualTakeover;
    private boolean localPaused;
    private long creativeGeneration;
    private AlertDialog spellDialog;
    private AccessibilityManager accessibilityManager;
    private final AccessibilityManager.TouchExplorationStateChangeListener accessibilityChanged = enabled -> { if (this.uiReady) selectMode(enabled); };
    private final int[] spellArt = {R.drawable.artemis_wink, R.drawable.artemis_curious, R.drawable.artemis_rest};
    private CheckBox hold, haptic;
    private OwnerHaptics ownerHaptics;
    private TouchPadView pad;
    private ImageView sticker;
    private android.graphics.drawable.Drawable awakeArt, scanningArt, restArt;
    private int expression = -1;
    private TouchModel.Feature[] selectedFeatures = new TouchModel.Feature[0];
    private double[] drafts = new double[0], lastSubmittedValues;
    private boolean[] sliderRunning = new boolean[0];
    private int selectedId = -1, bank;
    private long lastSubmittedRequest = -1, lastAcceptedLog;
    private boolean initialized, ready, scanning, stopping, pendingScan, visible, logDirty;
    private boolean binding, sliderMode, diagnosticsOpen, everSelected, disconnectedUnconfirmed, dark;
    private int background, surface, foreground, muted, border, cyan, violet, red, stopFill;
    private boolean remote, petRole, relayOnline;
    private RelayClient relay;
    private SessionGate session;
    private ControlRuntime runtime;
    private final ControlRuntime.Observer nativeObserver = event -> {
        syncRuntime();
        try { handle(event); } catch (JSONException e) { fail("Invalid device event"); }
    };
    private JSONObject relayState = new JSONObject();
    private TextView roleCaption, sessionCaption;
    private Button sessionAction;
    private String advertisedDevice = "", mirroredDevice = "", appliedEpoch = "";
    private long outgoingSequence, incomingSequence, appliedRequest;
    private double[] acceptedValues = new double[0];
    private boolean incomingHold;
    private long acceptedSequence;
    private SafeWordView safeWord;
    private boolean safeWordLatched;
    private boolean resettingRole;
    private boolean uiReady, receiverRegistered, resumeRequested;
    private final LinkedHashMap<Long, RemoteFrame> remoteRequests = new LinkedHashMap<>();
    private static final class RemoteFrame {
        final String epoch; final long sequence; final double[] values; final boolean hold;
        RemoteFrame(String epoch, long sequence, double[] values, boolean hold) { this.epoch = epoch; this.sequence = sequence; this.values = values.clone(); this.hold = hold; }
    }

    @Override public void onConnection(boolean connected, String reason) {
        relayOnline = connected;
        if (!connected) {
            advertisedDevice = ""; acceptedValues = new double[selectedFeatures.length]; appliedEpoch = "";
            resetLocal();
        }
        syncRuntime();
        setStatus(reason); updateEnabled();
    }
    @Override public void onRelay(JSONObject message) {
        try {
            switch (message.optString("type")) {
                case "state": {
                    relayState = message; uiError = ""; syncRuntime(); if(session.active())localPaused=false;
                    if (!message.optString("phase").equals("paired")) {
                        if (resettingRole) { RolePreferences.returnToPick(this); return; }
                        startActivity(new Intent(this, PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE, petRole ? "pet" : "owner")); finish(); return;
                    }
                    if (!message.optString("role").equals(petRole ? "pet" : "owner")) {
                        String role = message.optString("role"); RolePreferences.choose(this, role);
                        startActivity(new Intent(this, PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE, role)); finish(); return;
                    }
                    if (!petRole) bindRemoteDevice(message.optJSONObject("device"));
                    if (!session.active()) { resetLocal(); acceptedValues = new double[selectedFeatures.length]; }
                    JSONObject profile = message.optJSONObject("profile");
                    if (profile != null) roleCaption.setText(petRole ? profile.optString("pet") + " · " + profile.optString("species") : profile.optString("owner") + " → " + profile.optString("pet"));
                    setStatus(message.optString("reason", "Connected")); renderReadouts(); updateEnabled(); break;
                }
                case "applied":
                    if (!petRole && session.active() && message.optString("epoch").equals(session.epoch())) {
                        syncRuntime();
                        if (runtime.restoredAcknowledgment(message) && pad.getActiveTouchCount() == 0) mirrorAccepted(); else renderReadouts();
                    }
                    break;
                case "error": fail(message.optString("message", "Relay error")); break;
            }
        } catch (JSONException | IllegalArgumentException error) { requestStop("Invalid relay message · stopped"); }
    }
    private void syncRuntime() {
        if (runtime == null) return;
        initialized = runtime.initialized; ready = runtime.ready; stopping = runtime.stopping; safeWordLatched = runtime.safeWord;
        acceptedValues = runtime.accepted.clone(); incomingHold = runtime.hold;
        if (safeWord != null) safeWord.setLatched(safeWordLatched);
    }
    private double[] relayValues(JSONArray array) throws JSONException {
        if (array.length() != selectedFeatures.length) throw new JSONException("Feature count mismatch");
        double[] values = new double[array.length()];
        for (int i=0;i<values.length;i++) { double value = array.getDouble(i); if (!Double.isFinite(value) || value < 0 || value > 1) throw new JSONException("Invalid level"); values[i] = TouchModel.normalize(value, selectedFeatures[i].stepCount); }
        return values;
    }
    private void bindRemoteDevice(JSONObject device) throws JSONException {
        String signature = device == null ? "null" : device.toString();
        if (signature.equals(mirroredDevice)) return;
        mirroredDevice = signature; devices.clear(); selectedId = -1; bank = 0; acceptedValues = new double[0];
        if (device != null) { DeviceInfo value = new DeviceInfo(device, true); devices.put(value.id, value); selectedId = value.id; }
        refreshPicker(); bindDevice(); acceptedValues = new double[selectedFeatures.length];
    }
    private void advertiseDevice() {
        if (!pairedPet()) return;
        if (runtime.recoveringDevice) return;
        Object value = JSONObject.NULL; DeviceInfo device = devices.get(selectedId);
        if (initialized && ready && device != null && selectedFeatures.length > 0) {
            JSONArray features = new JSONArray();
            for (TouchModel.Feature feature : selectedFeatures) features.put(command("feature", "index", feature.scalarIndex, "label", feature.label, "steps", feature.stepCount));
            value = command("device", "id", device.id, "name", device.name, "features", features);
        }
        String signature = value.toString();
        if (!signature.equals(advertisedDevice)) { advertisedDevice = signature; runtime.select(value instanceof JSONObject ? (JSONObject)value : null); }
    }
    private void sessionActionTapped() {
        uiError = ""; localPaused=false; runtime.action(); syncRuntime(); updateSessionUi();
    }
    private void updateSessionUi() {
        if (sessionCaption == null) return;
        boolean online = relayOnline && relayState.optBoolean("online");
        String owner = "your owner"; JSONObject profile = relayState.optJSONObject("profile"); if (profile != null) owner = profile.optString("owner", owner);
        boolean resumable = relayState.optBoolean("resumeAllowed") && !relayState.optBoolean("safeWord") && session.canRecover();
        String state = !relayOnline ? "Reconnecting…" : !online ? "Waiting for the other phone…"
            : localPaused || relayState.optBoolean("paused") ? "Paused · resume when you like"
            : session.active() ? (petRole ? "Locked for " + owner + " ♡" : "Together ♡")
            : relayState.optBoolean("ready") ? (petRole ? "Ready · waiting for " + owner : "Your pet is ready")
            : "Ready when you are";
        if (scanning) state = "Finding your device…";
        if (!uiError.isEmpty() && online) state = (localPaused || relayState.optBoolean("paused") ? "Paused · " : "") + uiError;
        if (recording != null) state = "Recording your Ghost Hand…";
        if (playing != null) state = "Playing · " + playing.name;
        if (safeWordLatched || relayState.optBoolean("safeWord")) state = "Safe Word · stopped until your pet is ready";
        if (safeWordLatched && petRole) state = "Safe Word · stopped until you choose Ready";
        if (uiError.toLowerCase(Locale.US).contains("unconfirmed")) state = (safeWordLatched || relayState.optBoolean("safeWord") ? "Safe Word · " : localPaused || relayState.optBoolean("paused") ? "Paused · " : !relayOnline ? "Reconnecting · " : "") + "Stop unconfirmed. Check the device.";
        sessionCaption.setText(state);
        sessionAction.setText(!relayOnline ? "Reconnect" : session.active() ? "Together ♡" : session.pending() ? "One moment…" : resumable ? "Resume ♡" : petRole ? "I’m ready" : "Start");
        sessionAction.setEnabled(!relayOnline || online && !session.active() && session.mayRequest() && (resumable || (petRole ? nativeAvailable() && !relayState.optBoolean("ready") : relayState.optBoolean("ready"))));
        boolean findingToy = petRole ? runtime.recoveringDevice : relayState.has("deviceOnline") && !relayState.optBoolean("deviceOnline") && relayState.optJSONObject("device") != null;
        if (findingToy) {
            if (!safeWordLatched && !relayState.optBoolean("safeWord")) sessionCaption.setText(relayState.optBoolean("paused") ? "Paused · finding your toy again…" : "Finding your toy again… ♡");
            sessionAction.setText(petRole && safeWordLatched ? "Reconnect & ready ♡" : "Reconnecting…");
            sessionAction.setEnabled(petRole && safeWordLatched);
        }
        sessionAction.setVisibility(session.active() && !findingToy && relayOnline ? View.GONE : View.VISIBLE);
    }
    private void mirrorAccepted() {
        boolean previous = binding; binding = true; pad.setTargets(acceptedValues); pad.setHold(incomingHold); hold.setChecked(incomingHold);
        for (int i=0;i<Math.min(acceptedValues.length, sliders.size());i++) { drafts[i] = acceptedValues[i]; sliders.get(i).setProgress((int)Math.round(acceptedValues[i] * sliders.get(i).getMax())); }
        binding = previous; renderReadouts(); updateEnabled();
    }
    private void showPairDetails() {
        requestStop("Opening pair details · paused");
        JSONObject profile = relayState.optJSONObject("profile");
        StringBuilder message = new StringBuilder(profile == null ? "Your pair" : profile.optString("owner") + " + " + profile.optString("pet") + " · " + profile.optString("species"));
        DeviceInfo device = devices.get(selectedId);
        message.append("\n\n").append(device == null ? "No toy connected" : device.name).append("\n").append(capability.getText());
        new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Pair & device").setMessage(message.toString()).setPositiveButton("Close", null).setNegativeButton("Unpair", (dialog, which) -> new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Unpair these phones?").setMessage("Remote control will stop. You can pair again with a new invitation.").setNegativeButton("Keep pair", null).setPositiveButton("Unpair", (d, w) -> { requestStop("Unpairing…"); relay.send(command("unpair")); }).show()).show();
    }


    private final Runnable stopScan = () -> { if (initialized && visible) send(command("scan", "enabled", false)); };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            syncRuntime(); updateHaptics(); captureGesture();
            for (String message : BleDiagnostics.drain()) {
                log(message);
                if (message.startsWith("ERROR BLE")) {
                    scanning = false; pendingScan = false; handler.removeCallbacks(stopScan);
                    if (initialized) send(command("scan", "enabled", false)); setStatus(message);
                    // Runtime owns recovery/authority; diagnostic text must not turn
                    // a recoverable radio interruption into a deliberate Pause.
                }
            }
            if (logDirty && logView != null) logView.setText(String.join("\n", logs));
            logDirty = false; handler.postDelayed(this, 50);
        }
    };
    private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_OFF) return;
            if (remote) { runtime.bluetoothOff(); syncRuntime(); updateSessionUi(); return; }
            disconnectedUnconfirmed |= hasOutput(); pendingScan = false; ready = false; scanning = false;
            resetLocal();
            if (initialized) { stopping = true; send(command("suspend")); }
            if (remote) { session.stop(); relay.pause(); acceptedValues = new double[selectedFeatures.length]; advertiseDevice(); }
            fail(disconnectedUnconfirmed ? "Bluetooth off · output unconfirmed. Check the device." : "Bluetooth is off. Turn it on, then tap Scan.");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState); setPalette();
        remote = getIntent().getBooleanExtra(PairingActivity.EXTRA_REMOTE, false);
        petRole = "pet".equals(getIntent().getStringExtra(WelcomeActivity.EXTRA_ROLE));
        if (!remote) {
            String remembered = RolePreferences.get(this);
            startActivity(remembered.isEmpty() ? new Intent(this, WelcomeActivity.class) : new Intent(this, PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE, remembered));
            finish(); return;
        }
        if (remote) {
            runtime = ControlRuntime.get(this); runtime.configure(petRole); relay = runtime.relay; session = runtime.gate;
            engine = new DeviceBridge() {
                public String initialize() { return runtime.initialize(); }
                public String submit(String command) { return runtime.submit(command); }
                public String drain() { return "[]"; }
            };
        }
        AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
        accessibilityManager = accessibility;
        if (accessibility != null) accessibility.addTouchExplorationStateChangeListener(accessibilityChanged);
        sliderMode = accessibility != null && accessibility.isTouchExplorationEnabled();
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        LinearLayout root = column(); root.setBackgroundColor(background); root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom);
            } else view.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(12) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        // The entire body can scroll on short screens; Hold and Stop remain outside it.
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(), header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        sticker = new ImageView(this);
        awakeArt = getDrawable(R.drawable.artemis_wink); scanningArt = getDrawable(R.drawable.artemis_curious); restArt = getDrawable(R.drawable.artemis_rest);
        sticker.setImageDrawable(awakeArt);
        sticker.setScaleType(ImageView.ScaleType.FIT_CENTER); sticker.setContentDescription("Settings"); sticker.setFocusable(true); sticker.setOnClickListener(v -> showSettings());
        header.addView(sticker, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout title = column(); title.setPadding(dp(8), 0, 0, 0); title.addView(text("Heel", 18)); title.setMinimumHeight(dp(48)); title.setGravity(Gravity.CENTER_VERTICAL); title.setContentDescription("Settings"); title.setFocusable(true); title.setOnClickListener(v -> showSettings());
        String role = "pet".equals(getIntent().getStringExtra(WelcomeActivity.EXTRA_ROLE)) ? "Pet" : "Owner";
        roleCaption = text(role, 12); roleCaption.setTextColor(muted); title.addView(roleCaption);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        spellbook = new SpellBook(this);
        spellbookButton = button("Spellbook", this::showSpellbook);
        spellbookButton.setVisibility(petRole ? View.GONE : View.VISIBLE);
        header.addView(spellbookButton, new LinearLayout.LayoutParams(-2, dp(48)));
        body.addView(header);
        status = text("Connecting…", 13); status.setTextColor(muted);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout sessionRow = row(); sessionRow.setGravity(Gravity.CENTER_VERTICAL);
        sessionRow.setPadding(0, dp(4), 0, dp(4));
        sessionCaption = status; sessionRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        sessionAction = button(petRole ? "Ready" : "Start", this::sessionActionTapped);
        sessionRow.addView(sessionAction, new LinearLayout.LayoutParams(-2, -2)); body.addView(sessionRow);
        LinearLayout deviceRow = row(); deviceRow.setGravity(Gravity.CENTER_VERTICAL); body.addView(deviceRow);
        devicePicker = new Spinner(this); devicePicker.setContentDescription("Connected device");
        deviceRow.addView(devicePicker, new LinearLayout.LayoutParams(0, dp(48), 1));
        scan = button("Scan", this::scanTapped); deviceRow.addView(scan, new LinearLayout.LayoutParams(dp(72), -2));
        capability = text("No device selected.", 12); capability.setVisibility(View.GONE);
        bankPicker = new Spinner(this); bankPicker.setContentDescription("Vibration control pair"); bankPicker.setVisibility(View.GONE);
        body.addView(bankPicker, new LinearLayout.LayoutParams(-1, dp(48)));
        hint = text("Drag a paw up or down. Lift to stop.", 12); hint.setTextColor(muted); hint.setPadding(0, dp(2), 0, dp(2)); body.addView(hint);
        readouts = row(); body.addView(readouts);
        noControls = text("Scan connects supported devices automatically.", 14); noControls.setPadding(0, dp(12), 0, dp(12)); body.addView(noControls);
        pad = new TouchPadView(this); pad.setBackgroundPlayback(true); pad.setDarkTheme(dark); pad.setMinimumHeight(dp(160));
        body.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        sliderPanel = column(); body.addView(sliderPanel);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout options = row(); options.setGravity(Gravity.CENTER_VERTICAL);
        hold = new CheckBox(this); hold.setText("Hold on release"); hold.setTextSize(14); hold.setTextColor(foreground);
        hold.setTypeface(getResources().getFont(R.font.nunito)); hold.setFontVariationSettings("'wght' 500"); hold.setButtonTintList(ColorStateList.valueOf(cyan)); hold.setMinHeight(dp(48)); options.addView(hold, new LinearLayout.LayoutParams(0, -2, 1));
        haptic = new CheckBox(this); haptic.setText("Haptic"); haptic.setTextSize(14); haptic.setTextColor(foreground);
        haptic.setTypeface(getResources().getFont(R.font.nunito)); haptic.setFontVariationSettings("'wght' 500"); haptic.setButtonTintList(ColorStateList.valueOf(cyan)); haptic.setMinHeight(dp(48));
        haptic.setChecked(getPreferences(MODE_PRIVATE).getBoolean("haptic", false));
        haptic.setVisibility(petRole ? View.GONE : View.VISIBLE); options.addView(haptic, new LinearLayout.LayoutParams(0, -2, .65f));
        ownerHaptics = new OwnerHaptics(pad);
        haptic.setOnCheckedChangeListener((view, checked) -> { getPreferences(MODE_PRIVATE).edit().putBoolean("haptic", checked).apply(); if (checked) ownerHaptics.snapshot(); updateHaptics(); });
        recordingButton = button("Finish recording", this::finishRecording); recordingButton.setVisibility(View.GONE);
        root.addView(recordingButton);
        touchCount = text("0 touches", 12); touchCount.setTextColor(muted); touchCount.setVisibility(View.GONE); root.addView(options);
        if (remote && petRole) {
            safeWord = new SafeWordView(this); safeWord.setAction(() -> {
                runtime.safeWord(); safeWordLatched = true; resetLocal(); syncRuntime(); setStatus("Safe Word · stopped"); updateEnabled();
            });
            root.addView(safeWord, new LinearLayout.LayoutParams(-1, dp(64)));
        }
        stop = button("Stop all", () -> requestStop("Stopping…")); stop.setTextColor(Color.WHITE);
        stop.setBackground(buttonBackground(stopFill, red)); stop.setMinimumHeight(dp(48)); stop.setMinHeight(dp(48)); root.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (remote && petRole) pad.setLocked(true);
        if (remote && !petRole) { scan.setVisibility(View.GONE); devicePicker.setEnabled(false); devicePicker.setBackground(null); }
        pad.setListener(new TouchPadView.Listener() {
            @Override public void onTargetsChanged(double[] values) { if (!binding && !sliderMode && canControl()) submitFrame(values); }
            @Override public void onStateChanged() { if (!binding) {
                if (manualTakeover) { manualTakeover = false; pad.setHold(false); submitFrame(pad.getTargets(), true); }
                captureGesture(); renderReadouts(); updateEnabled();
            } }
            @Override public void onPlaybackTouched() { cancelPlayback(); manualTakeover = true; }
            @Override public void onCancelRequested() { if (!binding && !petRole) requestStop("Touch interrupted · stopping…"); }
        });
        hold.setOnCheckedChangeListener((view, checked) -> { if (!binding) {
            if (playing != null) { binding=true;hold.setChecked(false);binding=false;return; }
            if (remote && petRole) { boolean old = binding; binding = true; hold.setChecked(incomingHold); binding = old; }
            else { pad.setHold(checked); if (remote && session.active()) submitFrame(pad.getTargets(), true); }
        } });
        devicePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= deviceOptions.size()) return;
                int chosen = deviceOptions.get(position); if (chosen == selectedId) return;
                requestStop("Device changed · stopping…"); selectedId = chosen; bank = 0; everSelected |= chosen >= 0; disconnectedUnconfirmed = false; bindDevice();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        bankPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position == bank) return; requestStop("Controls changed · stopping…"); bank = position; bindFeatures();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        refreshPicker(); bindDevice(); uiReady = true;
        log(diagnosticHeader()); updateEnabled();
    }
    @Override public void onResume() {
        super.onResume(); if (!uiReady) return; visible = true;
        ConnectionService.start(this); runtime.start(); runtime.observe(nativeObserver); syncRuntime();
        handler.removeCallbacks(poll); handler.post(poll); relay.attach(this);
        if (relay.connected()) {
            relayState = runtime.state;
            try {
                if (petRole) {
                    if (runtime.selected != null) {
                        selectedId = runtime.selected.getInt("id"); everSelected = true;
                        int scalar = runtime.selected.getJSONArray("features").getJSONObject(0).getInt("index");
                        for (int i=0;i<runtime.displayDevices().length();i++) { DeviceInfo info = new DeviceInfo(runtime.displayDevices().getJSONObject(i)); if (info.id == selectedId) for (int j=0;j<info.features.size();j++) if (info.features.get(j).scalarIndex == scalar) bank = j / 2; }
                    }
                    syncDevices(runtime.devices); bindDevice();
                }
                syncRuntime(); if (session.active() && acceptedValues.length == selectedFeatures.length) mirrorAccepted();
            } catch (JSONException e) { fail("Device list unavailable"); }
        }
        if (!runtime.safeWord) relay.connect(null);
        if (initialized && pairedPet() && !ready && !stopping && permissionsGranted()) send(command("resume"));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications",true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
        }
        updateEnabled();
    }
    @Override public void onPause() {
        if (!uiReady) { super.onPause(); return; }
        if (recording != null || playing != null) requestStop("Paused");
        cancelCreative();
        // Release transient touches, but keep explicit Hold and the service-owned session alive.
        if (!petRole && session.active() && !sliderMode) pad.releaseForBackground();
        visible = false; if (ownerHaptics != null) { ownerHaptics.gate("background",0); ownerHaptics.stop(); ownerHaptics.cancelTests(); } handler.removeCallbacks(poll); runtime.detach(nativeObserver); relay.detach(this); super.onPause();
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); requestStop("Layout changed · stopping…"); }
    @Override public void onDestroy() { cancelCreative(); if(accessibilityManager!=null)accessibilityManager.removeTouchExplorationStateChangeListener(accessibilityChanged); if (ownerHaptics != null) ownerHaptics.stop(); handler.removeCallbacksAndMessages(null); if (runtime != null) runtime.detach(nativeObserver); if (relay != null) relay.detach(this); super.onDestroy(); }
    private void selectMode(boolean useSliders) { if (sliderMode != useSliders) { if (!(remote && petRole)) requestStop("Control mode changed · stopping…"); sliderMode = useSliders; renderReadouts(); updateEnabled(); } }
    private boolean pairedPet() { return remote && petRole && relayOnline && !isFinishing() && relayState.optString("phase").equals("paired") && relayState.optString("role").equals("pet"); }
    private boolean nativeAvailable() { return runtime != null && runtime.available() && !diagnosticsOpen && devices.containsKey(selectedId) && selectedFeatures.length > 0; }
    private boolean canControl() { return remote && !petRole && visible && relayOnline && relayState.optString("phase").equals("paired") && relayState.optString("role").equals("owner") && session.active() && !diagnosticsOpen && selectedFeatures.length > 0; }
    private boolean hasOutput() {
        if (possiblyRunning.contains(selectedId)) return true;
        if (pad != null) for (double value : pad.getTargets()) if (value > 0) return true;
        for (int i = 0; i < sliderRunning.length; i++) if (sliderRunning[i] && drafts[i] > 0) return true; return false;
    }
    private void resetLocal() {
        cancelCreative();
        boolean previous = binding; binding = true;
        if (ownerHaptics != null) ownerHaptics.stop();
        if (pad != null) pad.clear(); if (hold != null) hold.setChecked(false);
        Arrays.fill(drafts, 0); Arrays.fill(sliderRunning, false); for (SeekBar slider : sliders) slider.setProgress(0);
        lastSubmittedValues = null; lastSubmittedRequest = -1; binding = previous; if (pad != null && !binding) renderReadouts();
    }
    private void requestStop(String reason) {
        localPaused=true;
        cancelCreative(); if (runtime != null) runtime.pause(true); resetLocal(); syncRuntime(); setStatus(reason); updateEnabled();
    }
    private void stopLocally(String reason) {
        if (runtime != null) runtime.pause(false);
        if (remote) { acceptedValues = new double[selectedFeatures.length]; appliedEpoch = ""; incomingHold = false; remoteRequests.clear(); }
        resetLocal();
        syncRuntime();
        setStatus(reason); updateEnabled();
    }
    private boolean submitFrame(double[] targets) { return submitFrame(targets, false); }
    private boolean submitFrame(double[] targets, boolean force) {
        if (!canControl() || targets.length != selectedFeatures.length) return false;
        if (!force && Arrays.equals(lastSubmittedValues, targets)) return true;
        if (remote) {
            JSONArray values = new JSONArray(); boolean zero = false;
            for (int i=0;i<targets.length;i++) { double value = TouchModel.normalize(targets[i], selectedFeatures[i].stepCount); values.put(Double.valueOf(value)); zero |= value == 0 && lastSubmittedValues != null && lastSubmittedValues[i] > 0; }
            relay.frame(command("frame", "epoch", session.epoch(), "seq", ++runtime.outgoingSequence, "values", values, "hold", playing == null && pad.isHolding()), zero);
            lastSubmittedValues = targets.clone(); return relayOnline && session.active();
        }
        return submitNativeFrame(targets);
    }
    private boolean submitNativeFrame(double[] targets) {
        if (!nativeAvailable() || targets.length != selectedFeatures.length) return false;
        JSONArray values = new JSONArray();
        try { for (int i = 0; i < targets.length; i++) { JSONObject value = new JSONObject(); value.put("index", selectedFeatures[i].scalarIndex); value.put("value", targets[i]); values.put(value); } }
        catch (JSONException e) { requestStop("Invalid setpoint · stopping…"); return false; }
        long request = REQUESTS.incrementAndGet();
        long inputUptime = sliderMode ? SystemClock.uptimeMillis() : pad.getLastInputUptime(), enqueueUptime = SystemClock.uptimeMillis();
        if (send(command("vibration_frame", "device", selectedId, "request", request, "values", values))) {
            if (BleDiagnostics.isVerbose()) log("Timing request=" + request + " inputUptime=" + inputUptime + " JNIenqueueUptime=" + enqueueUptime + " JNIreturnUptime=" + SystemClock.uptimeMillis());
            lastSubmittedRequest = request; lastSubmittedValues = targets.clone();
            for (double target : targets) if (target > 0) possiblyRunning.add(selectedId);
            return true;
        }
        requestStop("Control error · stopping…"); return false;
    }
    private void submitSliders() {
        cancelPlayback();
        double[] targets = new double[drafts.length]; for (int i = 0; i < drafts.length; i++) targets[i] = sliderRunning[i] ? drafts[i] : 0;
        for (int i = 0; i < targets.length; i++) targets[i] = TouchModel.normalize(targets[i], selectedFeatures[i].stepCount);
        if (!submitFrame(targets)) return; // A rejected frame must never restore targets after Stop.
        boolean previous = binding; binding = true; pad.setTargets(targets); binding = previous;
        captureGesture(); renderReadouts(); updateEnabled();
    }
    private void bindDevice() {
        boolean previous = binding; binding = true; DeviceInfo device = devices.get(selectedId); ArrayList<String> banks = new ArrayList<>();
        if (device != null) for (int i = 0; i < device.features.size(); i += 2) banks.add("Controls " + (i + 1) + "–" + Math.min(i + 2, device.features.size()));
        if (banks.isEmpty()) banks.add("No vibration controls"); bank = Math.max(0, Math.min(bank, banks.size() - 1));
        bankPicker.setAdapter(adapter(banks)); bankPicker.setSelection(bank); bankPicker.setVisibility(device != null && device.features.size() > 2 ? View.VISIBLE : View.GONE);
        binding = previous; bindFeatures();
    }
    private void bindFeatures() {
        cancelCreative();
        boolean previous = binding; binding = true; DeviceInfo device = devices.get(selectedId); ArrayList<TouchModel.Feature> chosen = new ArrayList<>();
        if (device != null) for (int i = bank * 2; i < Math.min(bank * 2 + 2, device.features.size()); i++) chosen.add(device.features.get(i));
        selectedFeatures = chosen.toArray(new TouchModel.Feature[0]); pad.configure(selectedFeatures); hold.setChecked(false);
        drafts = new double[selectedFeatures.length]; sliderRunning = new boolean[selectedFeatures.length]; lastSubmittedValues = null; lastSubmittedRequest = -1;
        readouts.removeAllViews(); sliderPanel.removeAllViews(); pointValues.clear(); pointStates.clear(); sliders.clear(); sliderValues.clear(); sliderStarts.clear(); sliderStops.clear();
        for (int i = 0; i < selectedFeatures.length; i++) {
            final int point = i; TouchModel.Feature feature = selectedFeatures[i];
            LinearLayout box = column(); box.setPadding(dp(3), dp(3), dp(6), dp(6));
            TextView name = text((i + 1) + " · " + feature.label, 13); name.setTextColor(i == 0 ? cyan : violet); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); box.addView(name);
            LinearLayout levelRow = row(); levelRow.setGravity(Gravity.CENTER_VERTICAL); box.addView(levelRow);
            TextView value = text("0%", 24); value.setSingleLine(true); pointValues.add(value); levelRow.addView(value, new LinearLayout.LayoutParams(0,-2,1));
            TextView state = text("Rest", 12); state.setSingleLine(true); state.setTextColor(muted); pointStates.add(state); levelRow.addView(state);
            box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); readouts.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
            sliderPanel.addView(text((i + 1) + " · " + feature.label, 15));
            TextView sliderValue = text("Level: 0% · stopped", 13); sliderValues.add(sliderValue); sliderPanel.addView(sliderValue);
            SeekBar slider = new SeekBar(this); slider.setMax(feature.stepCount > 0 ? feature.stepCount : 1000); slider.setMinimumHeight(dp(48)); slider.setContentDescription(feature.label + " level");
            slider.setProgressTintList(ColorStateList.valueOf(i == 0 ? cyan : violet)); slider.setThumbTintList(ColorStateList.valueOf(i == 0 ? cyan : violet));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { if (binding || !fromUser) return;
                    if (playing != null) { cancelPlayback();restControl(); }
                    if (remote && petRole) { binding = true; bar.setProgress((int)Math.round((acceptedValues.length > point ? acceptedValues[point] : 0) * bar.getMax())); binding = false; return; }
                    drafts[point] = TouchModel.normalize(value / (double)bar.getMax(), selectedFeatures[point].stepCount); if (sliderRunning[point]) submitSliders(); else renderReadouts(); }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            sliders.add(slider); sliderPanel.addView(slider, new LinearLayout.LayoutParams(-1, dp(52)));
            LinearLayout controls = row();
            Button start = button(remote && petRole ? "Locked ♡" : "Start " + (i + 1), () -> { if (canControl()) { sliderRunning[point] = true; submitSliders(); } }); start.setContentDescription("Start " + feature.label);
            Button end = button(remote && petRole ? "Pause" : "Stop " + (i + 1), () -> { if (remote && petRole) requestStop("Paused by you"); else if (canControl()) { sliderRunning[point] = false; submitSliders(); } }); end.setContentDescription("Stop " + feature.label);
            controls.addView(start, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(end, new LinearLayout.LayoutParams(0, -2, 1)); sliderStarts.add(start); sliderStops.add(end); sliderPanel.addView(controls);
        }
        noControls.setText(device == null ? "Choose a connected device, or tap Scan." : "No vibration controls reported. Other actuator types are not mapped to this pad.");
        binding = previous; renderReadouts(); updateEnabled(); if (remote && petRole) advertiseDevice();
    }
    static String formatPercent(double value) {
        long rounded = Math.round(value * 100);
        return value > .05 && rounded == 5 ? ">5%" : rounded + "%";
    }
    private void renderReadouts() {
        if (pad == null || hold == null) return; double[] values = pad.getVisualTargets();
        for (int i = 0; i < Math.min(values.length, pointValues.size()); i++) {
            pointValues.get(i).setText(formatPercent(values[i]));
            pointStates.get(i).setText(!relayOnline || !devices.containsKey(selectedId) || !relayState.optBoolean("deviceOnline",true) ? "Disconnected" : !session.active() ? "Paused" : playing != null ? "Playing" : pad.isPointActive(i) ? "Touching" : values[i] > 0 ? "Held" : "Rest");
        }
        for (int i = 0; i < sliderValues.size(); i++) sliderValues.get(i).setText("Level: " + formatPercent(playing != null ? values[i] : drafts[i]) + " · " + (playing != null ? "Playing" : sliderRunning[i] ? "Running" : "Rest"));
        boolean previous = binding; binding = true; hold.setChecked(pad.isHolding()); binding = previous;
        if (remote && petRole) for (int i=0;i<Math.min(pointValues.size(), acceptedValues.length);i++) {
            pointValues.get(i).setText(formatPercent(acceptedValues[i]));
            pointStates.get(i).setText(session.active() ? (petRole ? "Owner’s level" : "Device level") : "Paused");
        }
        int touches = pad.getActiveTouchCount(); touchCount.setText(remote && petRole ? "Locked ♡" : touches + (touches == 1 ? " touch" : " touches"));
        hint.setText(sliderMode ? "Choose a level, then Start. Adjustments are live while running." : pad.isHolding() ? "Drag a paw to adjust. Lift to keep." : "Drag a paw up or down. Lift to stop.");
        if (remote && petRole) hint.setText("A little peek. These controls belong to your owner.");
        pad.setContentDescription(hint.getText()); hint.setVisibility(sliderMode && selectedFeatures.length > 0 ? View.VISIBLE : View.GONE); noControls.setVisibility(selectedFeatures.length == 0 ? View.VISIBLE : View.GONE);
        pad.setVisibility(!sliderMode && selectedFeatures.length > 0 ? View.VISIBLE : View.GONE); readouts.setVisibility(!sliderMode && selectedFeatures.length > 0 ? View.VISIBLE : View.GONE);
        sliderPanel.setVisibility(sliderMode && selectedFeatures.length > 0 ? View.VISIBLE : View.GONE);
        hold.setVisibility(View.VISIBLE);
    }

    private void refreshPicker() {
        boolean previous = binding; binding = true; ArrayList<String> names = new ArrayList<>(); deviceOptions.clear(); names.add("Select a device"); deviceOptions.add(-1); int selection = 0;
        for (DeviceInfo device : devices.values()) { deviceOptions.add(device.id); names.add(device.name); if (device.id == selectedId) selection = deviceOptions.size() - 1; }
        devicePicker.setAdapter(adapter(names)); devicePicker.setSelection(selection); binding = previous;
    }
    private void syncDevices(JSONArray array) throws JSONException {
        if (!pairedPet()) return;
        if (runtime.recoveringDevice) array = runtime.displayDevices();
        Set<Integer> found = new HashSet<>(); boolean selectedChanged = false;
        for (int i = 0; i < array.length(); i++) {
            DeviceInfo incoming = new DeviceInfo(array.getJSONObject(i)); found.add(incoming.id); DeviceInfo old = devices.get(incoming.id);
            if (old == null) { devices.put(incoming.id, incoming); log("Connected: " + incoming.name + " · #" + incoming.id); }
            else if (!old.signature.equals(incoming.signature)) {
                if (incoming.id == selectedId) { requestStop("Device capabilities changed · stopping…"); selectedChanged = true; }
                incoming.batteryText = old.batteryText; devices.put(incoming.id, incoming);
            }
        }
        for (int id : new ArrayList<>(devices.keySet())) if (!found.contains(id)) {
            devices.remove(id); log("Disconnected: device " + id);
            if (id == selectedId) {
                disconnectedUnconfirmed |= hasOutput(); requestStop("Device disconnected · clearing controls…"); selectedId = -1; bank = 0; selectedChanged = true;
                setStatus(disconnectedUnconfirmed ? "Device disconnected · output unconfirmed. Check the device." : "Device disconnected.");
            }
        }
        if (!everSelected && selectedId < 0 && !devices.isEmpty()) { selectedId = devices.keySet().iterator().next(); everSelected = true; selectedChanged = true; }
        refreshPicker(); if (selectedChanged) bindDevice(); else updateCapability();
    }
    private void updateCapability() {
        DeviceInfo device = devices.get(selectedId);
        capability.setText(device == null ? (devices.isEmpty() ? "No devices connected." : "Choose a device above.") : device.features.size() + " vibration control(s)" + (device.batteryText == null ? "" : " · " + device.batteryText));
    }
    private static final class DeviceInfo {
        final int id; final String name, signature; final boolean battery; String batteryText;
        final ArrayList<TouchModel.Feature> features = new ArrayList<>();
        DeviceInfo(JSONObject object, boolean remote) throws JSONException {
            id = object.getInt("id"); name = object.getString("name"); battery = false; signature = object.toString();
            JSONArray source = object.getJSONArray("features"); if (source.length() < 1 || source.length() > 2) throw new JSONException("Invalid features");
            for (int i=0;i<source.length();i++) { JSONObject f = source.getJSONObject(i); features.add(new TouchModel.Feature(f.getInt("index"), f.getString("label"), f.getInt("steps"))); }
        }
        DeviceInfo(JSONObject object) throws JSONException {
            id = object.getInt("id"); name = object.getString("name"); battery = object.optBoolean("battery"); JSONObject messages = object.getJSONObject("messages"); signature = name + messages.toString();
            JSONArray scalar = messages.optJSONArray("ScalarCmd");
            if (scalar != null) for (int i = 0; i < scalar.length(); i++) {
                JSONObject feature = scalar.getJSONObject(i); if (!"Vibrate".equals(feature.optString("ActuatorType"))) continue;
                String descriptor = feature.optString("FeatureDescriptor", "").trim();
                String label = descriptor.isEmpty() || descriptor.equals("N/A") ? "Vibration " + (features.size() + 1) : descriptor;
                // The array position is the original scalar command index, even for mixed actuator types.
                features.add(new TouchModel.Feature(i, label, Math.max(0, feature.optInt("StepCount", 0))));
            }
        }
    }
    private void handle(JSONObject event) throws JSONException {
        switch (event.getString("type")) {
            case "ready":
                ready = event.optBoolean("active", false); if (!stopping && !disconnectedUnconfirmed) setStatus(ready ? "Ready · " + devices.size() + " device(s)" : "Paused. Tap Scan to resume.");
                if (remote && !ready && session.active()) requestStop("Device engine paused · stopped");
                if (pendingScan && visible && ready && !stopping) beginScan(); break;
            case "devices": syncDevices(event.getJSONArray("devices")); break;
            case "scanning":
                scanning = event.getBoolean("enabled"); if (!scanning) handler.removeCallbacks(stopScan);
                if (!disconnectedUnconfirmed && !stopping) setStatus(scanning ? "Scanning for Lovense devices… (15 seconds)" : "Scan stopped · " + devices.size() + " device(s)"); break;
            case "stopped":
                if (event.getBoolean("confirmed")) possiblyRunning.clear();
                stopping = false; ready = event.getBoolean("active"); resetLocal();
                setStatus(disconnectedUnconfirmed ? "Device disconnected · output unconfirmed. Check the device." : event.getBoolean("confirmed") ? (ready ? "Stop accepted." : "Paused. Tap Scan to resume.") : "Stop unconfirmed. Check the device.");
                log(status.getText().toString()); if (pendingScan && visible && ready) beginScan(); break;
            case "vibration_applied":
                if (petRole) { syncRuntime(); if (acceptedValues.length == selectedFeatures.length) mirrorAccepted(); }
                if (BleDiagnostics.isVerbose()) log("Timing request=" + event.optLong("request") + " nativeQueueMs=" + event.optLong("queue_ms") + " nativeIoMs=" + event.optLong("io_ms") + " ACKdrainUptime=" + SystemClock.uptimeMillis());
                RemoteFrame applied = remoteRequests.remove(event.optLong("request"));
                if (remote && petRole && applied != null && session.active() && applied.epoch.equals(session.epoch()) && applied.sequence > acceptedSequence && !stopping) {
                    acceptedSequence = applied.sequence; acceptedValues = applied.values.clone(); incomingHold = applied.hold; mirrorAccepted();
                    relay.send(command("applied", "epoch", applied.epoch, "seq", applied.sequence, "values", new JSONArray(applied.values)));
                }
                if (event.getInt("device") == selectedId && event.getLong("request") == lastSubmittedRequest && !stopping) {
                    boolean allZero = lastSubmittedValues != null;
                    if (lastSubmittedValues != null) for (double value : lastSubmittedValues) if (value > 0) allZero = false;
                    if (allZero) possiblyRunning.remove(selectedId);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastAcceptedLog > 1000) { log("Vibration frame " + lastSubmittedRequest + " accepted for #" + selectedId); lastAcceptedLog = now; }
                } break;
            case "vibration_release_applied": break;
            case "battery":
                DeviceInfo device = devices.get(event.getInt("device")); if (device != null) { device.batteryText = "Battery " + event.getInt("percent") + "%"; updateCapability(); log(device.name + ": " + device.batteryText); } break;
            case "error": if (remote && !runtime.recoveringDevice) requestStop("Device command failed · output unconfirmed"); if (runtime.recoveringDevice) log(event.getString("message")); else fail(event.getString("message")); break;
            case "fatal": ready = false; if (remote) requestStop("Device engine stopped"); resetLocal(); fail(event.getString("message")); break;
            case "log": log(event.getString("message")); break;
        } updateExpression(); updateEnabled(); if (remote && petRole) advertiseDevice();
    }
    private String[] permissions() { return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION}; }
    private boolean permissionsGranted() { for (String permission : permissions()) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false; return true; }
    private void scanTapped() {
        if (runtime != null) { if (runtime.safeWord || runtime.recoveringDevice) return; runtime.manualScan(); }
        if (!pairedPet()) { setStatus("Pair and connect this phone as Pet before scanning."); return; }
        log("Scan tapped; permissions=" + permissionsGranted() + "; engine=" + initialized);
        if (scanning) { send(command("scan", "enabled", false)); return; }
        if (!permissionsGranted()) { requestPermissions(permissions(), 10); return; }
        BluetoothManager manager = getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { fail("This device has no Bluetooth adapter."); return; }
        try { if (!adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 11); return; } }
        catch (SecurityException e) { fail("Bluetooth permission changed. Grant Nearby devices and tap Scan again."); return; }
        if (Build.VERSION.SDK_INT <= 30) {
            LocationManager location = getSystemService(LocationManager.class);
            boolean enabled = Build.VERSION.SDK_INT >= 28 ? location.isLocationEnabled() : location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!enabled) { fail("Android 8–11 requires Location on for BLE scanning."); startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return; }
        }
        try {
            pendingScan = true;
            if (!initialized) {
                setStatus("Starting device engine…"); String error = engine.initialize();
                if (error == null || !error.isEmpty()) { pendingScan = false; fail("Engine startup: " + error); return; }
                initialized = true; log("Native engine initialized; waiting for ready event");
            }
            send(command("resume")); // Refresh the process-wide engine after Activity recreation too.
            if (ready && !stopping) beginScan(); updateEnabled();
        } catch (RuntimeException | LinkageError e) { pendingScan = false; fail("Cannot start native engine: " + e.getMessage()); }
    }
    private void beginScan() { pendingScan = false; if (send(command("scan", "enabled", true))) { setStatus("Starting scan…"); handler.removeCallbacks(stopScan); handler.postDelayed(stopScan, 15000); } }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request, permissions, results); if (request == 10) { if (permissionsGranted()) scanTapped(); else fail("Bluetooth permission denied. Allow Nearby devices (or Location) in app settings."); } }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == 11 && result == RESULT_OK) handler.post(this::scanTapped); }

    private void showDiagnostics() {
        requestStop("Opening diagnostics · stopping…"); diagnosticsOpen = true; updateEnabled();
        LinearLayout content = column(); content.setPadding(dp(14), dp(6), dp(14), dp(8)); content.setBackgroundColor(background);
        content.addView(button("Copy debug log", () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Heel debug log", diagnosticHeader() + "\n\n" + String.join("\n", logs)));
            Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show();
        }));
        CheckBox verbose = new CheckBox(this); verbose.setText("Verbose packet + timing log"); verbose.setTextColor(foreground);
        verbose.setChecked(BleDiagnostics.isVerbose()); verbose.setOnCheckedChangeListener((view, checked) -> BleDiagnostics.setVerbose(checked)); content.addView(verbose);
        DeviceInfo selected = devices.get(selectedId);
        if (selected != null && selected.battery) {
            batteryRead = button("Read selected device battery", () -> send(command("battery", "device", selected.id)));
            content.addView(batteryRead); updateEnabled();
        }
        content.addView(text("Logs contain nearby Bluetooth names and packet previews. Review before sharing.", 12));
        ScrollView scroll = new ScrollView(this); logView = text(String.join("\n", logs), 11); logView.setTypeface(Typeface.MONOSPACE); logView.setTextIsSelectable(true); scroll.addView(logView);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(260))); content.addView(button("Clear log", () -> { logs.clear(); logDirty = true; }));
        AlertDialog dialog = new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Debug log").setView(content).setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(d -> { logView = null; batteryRead = null; diagnosticsOpen = false; updateEnabled(); }); dialog.show();
    }
    private void showHapticDiagnostics() {
        if (petRole || !visible || ownerHaptics == null) return;
        requestStop("Haptic diagnostics · paused"); diagnosticsOpen = true; updateEnabled(); ownerHaptics.snapshot();
        LinearLayout content = dialogBody();
        content.addView(text("These tests buzz this phone only. Toy controls are paused. Try A, B and C separately, with a two-second gap.",14));
        TextView report = text(ownerHaptics.diagnostics(),11); report.setTypeface(Typeface.MONOSPACE); report.setTextIsSelectable(true);
        String[] labels = {"A · Standard click", "B · Short pulse", "C · Custom tick"};
        for (int i=0;i<labels.length;i++) {
            final int kind=i; Button test=button(labels[i],()->{});
            test.setOnClickListener(v->{ if (visible && diagnosticsOpen && !petRole) { ownerHaptics.test(kind,v); report.setText(ownerHaptics.diagnostics()); } });
            if (kind==2 && Build.VERSION.SDK_INT<30) test.setEnabled(false);
            content.addView(test);
        }
        content.addView(button("Copy haptic report",()->{
            ownerHaptics.snapshot(); String value=ownerHaptics.diagnostics(); report.setText(value);
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Heel haptic report",value));
            Toast.makeText(this,"Haptic report copied",Toast.LENGTH_SHORT).show();
        }));
        content.addView(text("The report contains phone/build details and haptic events, not pairing codes or Bluetooth packet logs. It cannot tell whether you felt the vibration.",12));
        content.addView(report); ScrollView scroll=new ScrollView(this); scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Haptic diagnostics").setView(scroll).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->{ ownerHaptics.cancelTests(); diagnosticsOpen=false; updateEnabled(); });
        dialog.show(); styleSpellDialog(dialog);
    }
    private void showSettings() {
        requestStop("Settings · paused");
        String[] choices = remote ? (petRole ? new String[]{"Pair & device", "Debug log", "Reset role choice"} : new String[]{"Pair & device", "Haptic diagnostics", "Debug log", "Reset role choice"}) : new String[]{"Debug log", "Reset role choice"};
        new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Settings").setItems(choices, (dialog, which) -> {
            String choice = choices[which];
            if (choice.equals("Pair & device")) showPairDetails();
            else if (choice.equals("Haptic diagnostics")) showHapticDiagnostics();
            else if (choice.equals("Debug log")) showDiagnostics();
            else if (!remote) RolePreferences.returnToPick(this);
            else if (!relayOnline) new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Reconnect first").setMessage("Heel needs to unpair the phones before changing your role. Your controls are already paused.").setPositiveButton("Close", null).show();
            else new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Unpair and choose again?").setMessage("This stops the pair and returns to Pick. This phone’s setup identity is kept, so you won’t need another setup code.").setNegativeButton("Keep role", null)
                    .setPositiveButton("Unpair & reset", (d,w) -> { requestStop("Unpairing…"); resettingRole = true; if (!relay.send(command("unpair"))) { resettingRole = false; setStatus("Reconnect to reset your role."); } }).show();
        }).setNegativeButton("Close", null).show();
    }

    private boolean canPerformSpell() {
        return canControl() && !stopping && !safeWordLatched && !relayState.optBoolean("safeWord")
            && !relayState.optBoolean("paused") && relayState.optBoolean("online")
            && relayState.optBoolean("deviceOnline", true);
    }
    private void cancelPlayback() {
        playing = null; handler.removeCallbacks(playbackTick); manualTakeover = false;
        if (pad != null) pad.setGhost(false);
    }
    private void cancelCreative() {
        creativeGeneration++;
        recording = null; handler.removeCallbacks(recordingLimit); cancelPlayback();
    }
    private void restControl() {
        boolean old = binding; binding = true; pad.clear();
        Arrays.fill(drafts,0);Arrays.fill(sliderRunning,false);for(SeekBar slider:sliders)slider.setProgress(0);
        binding = old;
        submitFrame(new double[selectedFeatures.length], true); renderReadouts(); updateEnabled();
    }
    private final Runnable recordingLimit = this::finishRecording;
    private void startRecording() {
        if (!canPerformSpell()) { Toast.makeText(this,"Start a session with your pet first",Toast.LENGTH_SHORT).show(); return; }
        cancelCreative(); restControl();
        recording = new SpellBook.Recording(selectedFeatures.length, SystemClock.uptimeMillis());
        handler.postDelayed(recordingLimit, SpellBook.MAX_DURATION); updateEnabled();
    }
    private void captureGesture() {
        if (recording == null) return;
        int touching = 0;
        for (int i=0;i<selectedFeatures.length;i++) if (pad.isPointActive(i) || sliderMode && sliderRunning[i]) touching |= 1<<i;
        if (!recording.add(SystemClock.uptimeMillis(),pad.getVisualTargets(),touching)) handler.post(recordingLimit);
    }
    private void finishRecording() {
        if (recording == null) return;
        SpellBook.Recording draft = recording; long finished = SystemClock.uptimeMillis();
        recording = null; handler.removeCallbacks(recordingLimit); restControl();
        if (!draft.hasMovement()) { Toast.makeText(this,"Move a paw to make a recording",Toast.LENGTH_SHORT).show(); return; }
        LinearLayout content = dialogBody();
        content.addView(text("A name and a little face for your spell.",14));
        EditText name = new EditText(this); name.setSingleLine(true); name.setTextColor(foreground); name.setHintTextColor(muted);
        name.setTypeface(getResources().getFont(R.font.nunito)); name.setFontVariationSettings("'wght' 500"); name.setHint("Spell name"); name.setContentDescription("Spell name");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)}); content.addView(name);
        boolean largeText=getResources().getConfiguration().fontScale>1.3f;
        RadioGroup choices = new RadioGroup(this); choices.setOrientation(largeText?RadioGroup.VERTICAL:RadioGroup.HORIZONTAL);
        String[] labels = {"Wink","Curious","Rest"};
        int[] choiceIds = {R.id.spell_wink,R.id.spell_curious,R.id.spell_rest};
        for (int i=0;i<spellArt.length;i++) {
            RadioButton choice = new RadioButton(this); choice.setId(choiceIds[i]); choice.setText(labels[i]); choice.setTextColor(foreground);
            choice.setTypeface(getResources().getFont(R.font.nunito)); choice.setFontVariationSettings("'wght' 500"); choice.setButtonTintList(ColorStateList.valueOf(cyan));
            android.graphics.drawable.Drawable art = getDrawable(spellArt[i]); art.setBounds(0,0,dp(52),dp(52));
            if(largeText){choice.setCompoundDrawables(null,null,art,null);choice.setGravity(Gravity.CENTER_VERTICAL);choice.setMinHeight(dp(64));}
            else {choice.setCompoundDrawables(null,art,null,null);choice.setGravity(Gravity.CENTER);choice.setMinHeight(dp(96));}
            choices.addView(choice,largeText?new RadioGroup.LayoutParams(-1,-2):new RadioGroup.LayoutParams(0,-2,1));
        }
        choices.check(R.id.spell_wink); content.addView(choices);
        ScrollView form=new ScrollView(this);form.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Save your spell").setView(form)
            .setNegativeButton("Discard",null).setPositiveButton("Save",null).create();
        dialog.show(); dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); styleSpellDialog(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim(); if (title.isEmpty()) {name.setError("Give your spell a name");return;}
            int chosen=choices.getCheckedRadioButtonId()==R.id.spell_curious?1:choices.getCheckedRadioButtonId()==R.id.spell_rest?2:0;
            try { spellbook.save(draft.finish(finished,title,chosen)); dialog.dismiss(); Toast.makeText(this,"Saved to your Spellbook",Toast.LENGTH_SHORT).show(); }
            catch (IllegalArgumentException|IllegalStateException e) { name.setError(e.getMessage()); }
        });
    }
    private LinearLayout dialogBody() { LinearLayout content=column();content.setPadding(dp(16),dp(8),dp(16),dp(12));content.setBackgroundColor(background);return content; }
    private void styleSpellDialog(AlertDialog dialog) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(background);shape.setCornerRadius(dp(16));
        dialog.getWindow().setBackgroundDrawable(shape); styleSpellText(dialog.getWindow().getDecorView());
    }
    private void styleSpellText(View view) {
        if(view instanceof TextView){TextView text=(TextView)view;text.setTypeface(getResources().getFont(R.font.nunito));text.setFontVariationSettings(text.getTextSize()/getResources().getDisplayMetrics().scaledDensity>=18?"'wght' 700":"'wght' 500");
            if(view instanceof Button && !(view instanceof CheckBox) && !(view instanceof RadioButton)){((Button)view).setAllCaps(false);text.setTextColor(cyan);text.setFontVariationSettings("'wght' 600");}}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)styleSpellText(((ViewGroup)view).getChildAt(i));
    }
    private static String durationLabel(long millis) { return String.format(Locale.US,"%d:%02d",millis/60000,(millis/1000)%60); }
    private void postCreative(Runnable action) {
        long generation=creativeGeneration;String epoch=session.epoch(),device=mirroredDevice;
        handler.post(()->{if(generation==creativeGeneration&&visible&&epoch.equals(session.epoch())&&device.equals(mirroredDevice))action.run();});
    }
    private void showSpellbook() {
        if (petRole) return;
        if (playing != null) { cancelPlayback(); restControl(); return; }
        if (recording != null) { finishRecording(); return; }
        // Opening the book rests the current live output without revoking the paired session.
        restControl();
        LinearLayout content=dialogBody();
        content.addView(text("Your gestures, kept as little spells.",15));
        Button record=button("Record a Ghost Hand",()->{spellDialog.dismiss();postCreative(this::startRecording);});
        record.setEnabled(canPerformSpell());content.addView(record);
        content.addView(text(canPerformSpell()?"Record up to 2 minutes. Every spell starts and ends at rest.":"Start a session to record or play. Your saved spells stay here.",12));
        List<SpellBook.Spell> spells=spellbook.all();
        for(SpellBook.Spell spell:spells){
            LinearLayout item=row(); item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(0,dp(10),0,dp(10));
            ImageView image=new ImageView(this);image.setImageResource(spellArt[spell.sticker]);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(image,new LinearLayout.LayoutParams(dp(48),dp(48)));
            LinearLayout description=column();TextView title=text(spell.name,17);description.addView(title);
            description.addView(text(durationLabel(spell.duration)+" · "+spell.channels+(spell.channels==1?" paw":" paws"),12));
            item.addView(description,new LinearLayout.LayoutParams(0,-2,1));content.addView(item);
            LinearLayout actions=row();
            Button play=button("Play",()->{spellDialog.dismiss();postCreative(()->startPlayback(spell));});
            play.setContentDescription("Play "+spell.name);play.setEnabled(canPerformSpell()&&spell.channels==selectedFeatures.length);actions.addView(play,new LinearLayout.LayoutParams(0,-2,1));
            Button delete=button("Delete",()->new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Delete “"+spell.name+"”?").setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->{
                try{spellbook.delete(spell.id);spellDialog.dismiss();showSpellbook();}catch(IllegalStateException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
            }).show());delete.setContentDescription("Delete "+spell.name);actions.addView(delete,new LinearLayout.LayoutParams(0,-2,1));content.addView(actions);
            if(selectedFeatures.length>0&&spell.channels!=selectedFeatures.length)content.addView(text("Choose a device with "+spell.channels+(spell.channels==1?" control":" controls")+" to play this spell.",12));
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        spellDialog=new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("Spellbook").setView(scroll).setNegativeButton("Close",null).create();spellDialog.show();styleSpellDialog(spellDialog);
    }
    private void startPlayback(SpellBook.Spell spell) {
        if (!canPerformSpell() || spell.channels != selectedFeatures.length) { Toast.makeText(this,"Choose a matching device and start a session first",Toast.LENGTH_SHORT).show(); return; }
        cancelCreative(); restControl();
        playing=spell;playbackStarted=SystemClock.uptimeMillis();playbackEpoch=session.epoch();playbackDevice=mirroredDevice;
        pad.setGhost(true);updateEnabled();handler.post(playbackTick);
    }
    private final Runnable playbackTick = new Runnable() {
        public void run() {
            SpellBook.Spell spell=playing;if(spell==null)return;
            if(!canPerformSpell()||!playbackEpoch.equals(session.epoch())||!playbackDevice.equals(mirroredDevice)) { cancelPlayback();restControl();return; }
            long elapsed=SystemClock.uptimeMillis()-playbackStarted;
            if(elapsed>=spell.duration) {cancelPlayback();restControl();return;}
            boolean old=binding;binding=true;pad.setVisualTargets(spell.at(elapsed));
            for(int i=0;i<sliders.size();i++)sliders.get(i).setProgress((int)Math.round(pad.getTargets()[i]*sliders.get(i).getMax()));
            binding=old;
            if(!submitFrame(pad.getTargets())) {cancelPlayback();restControl();return;}
            renderReadouts();updateExpression();updateHaptics();handler.postDelayed(this,16);
        }
    };
    private boolean send(JSONObject message) {
        if (!initialized) return false;
        String type = message.optString("type");
        if (!pairedPet() && (type.equals("vibration_frame") || type.equals("resume") || type.equals("battery") || type.equals("scan") && message.optBoolean("enabled"))) return false;
        try { String error = engine.submit(message.toString()); if (error == null || !error.isEmpty()) { fail(error == null ? "Native call failed" : error); return false; } return true; }
        catch (RuntimeException | LinkageError e) { fail("Native call: " + e.getMessage()); return false; }
    }
    private void updateEnabled() {
        if (pad == null || stop == null) return;
        boolean control = canControl(), active = pad.getActiveTouchCount() > 0;
        boolean locked = remote && petRole && relayOnline && session.active() && selectedFeatures.length > 0;
        boolean previous = binding; binding = true; pad.setEnabled((control || locked) && !sliderMode); if (remote && petRole && acceptedValues.length == selectedFeatures.length) pad.setTargets(acceptedValues); binding = previous;
        scan.setText(scanning ? "Stop scan" : "Scan"); scan.setEnabled(pairedPet() && !stopping && !active && !hasOutput() && !safeWordLatched && !runtime.recoveringDevice); stop.setEnabled(remote || initialized); if (remote) stop.setText("Stop & pause");
        if (batteryRead != null) batteryRead.setEnabled(initialized && visible && ready && !stopping && !hasOutput() && devices.containsKey(selectedId));
        devicePicker.setEnabled(pairedPet() && !active && !stopping && !diagnosticsOpen && !devices.isEmpty()); bankPicker.setEnabled(pairedPet() && !active && !stopping && !diagnosticsOpen);
        hold.setEnabled(control && !sliderMode && playing == null);
        for (int i = 0; i < sliders.size(); i++) { sliders.get(i).setEnabled((control || locked) && sliderMode); sliderStarts.get(i).setEnabled((control || locked) && sliderMode && !sliderRunning[i]); sliderStops.get(i).setEnabled((control || locked) && sliderMode && (locked || sliderRunning[i])); }
        if ((playing != null || recording != null) && !canPerformSpell()) { cancelCreative(); restControl(); }
        if (recordingButton != null) recordingButton.setVisibility(recording != null ? View.VISIBLE : View.GONE);
        if (spellbookButton != null) spellbookButton.setText(playing != null ? "Stop spell" : "Spellbook");
        updateCapability(); updateExpression(); if (remote) updateSessionUi(); updateHaptics();
    }
    private void updateHaptics() {
        if (ownerHaptics == null || pad == null || haptic == null) return;
        double level = 0; double[] output = pad.getTargets(), visual = pad.getVisualTargets();
        for (int i = 0; i < output.length; i++) if (output[i] > 0) level = Math.max(level, visual[i]);
        String reason = petRole ? "pet-role" : !visible ? "background" : !haptic.isChecked() ? "checkbox-off"
            : diagnosticsOpen ? "diagnostics-open" : safeWordLatched || relayState.optBoolean("safeWord") ? "safe-word"
            : relayState.optBoolean("paused") ? "paused" : !relayOnline ? "relay-offline"
            : !relayState.optBoolean("deviceOnline",true) ? "device-offline"
            : !canControl() ? "no-active-owner-control" : level <= TouchModel.REST_LIMIT ? "rest" : "enabled";
        ownerHaptics.gate(reason,level);
        ownerHaptics.update(reason.equals("enabled") ? level : 0);
    }
    private void updateExpression() {
        if (sticker == null || pad == null) return;
        // Rest is only shown with a connected, ready, confirmed-zero device.
        int next = scanning || !relayOnline || runtime.recoveringDevice || !relayState.optBoolean("deviceOnline",true) ? 1 : !stopping && devices.containsKey(selectedId) && !hasOutput() ? 2 : 0;
        if (expression == next) return;
        expression = next; sticker.setImageDrawable(next == 1 ? scanningArt : next == 2 ? restArt : awakeArt);
    }
    private void setStatus(String message) {
        if (message != null) {
            log(message);
            if (message.startsWith("ERROR") || message.toLowerCase(Locale.US).contains("unconfirmed")) uiError=message;
        }
        if (sessionCaption != null) updateSessionUi();
    }
    private void fail(String message) { uiError = message == null ? "Something went wrong" : message; log(message); updateEnabled(); }
    private void log(String message) {
        if (message == null) message = "Unknown error"; message = message.replaceAll("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b", "[BLE address]");
        if (message.length() > 1200) message = message.substring(0, 1200) + "…";
        logs.addLast(String.format(Locale.US, "%tT  %s", System.currentTimeMillis(), message)); while (logs.size() > 200) logs.removeFirst(); logDirty = true;
    }
    private String diagnosticHeader() { return "Heel 0.9.2 / build 17 | " + Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | permissions=" + permissionsGranted() + " | ready=" + ready + " | scanning=" + scanning + " | selected=" + selectedId + " | mode=" + (sliderMode ? "sliders" : "touch"); }
    private JSONObject command(String type, Object... fields) {
        JSONObject result = new JSONObject(); try { result.put("type", type); for (int i = 0; i < fields.length; i += 2) result.put((String)fields[i], fields[i + 1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); } return result;
    }
    private ArrayAdapter<String> adapter(ArrayList<String> labels) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convert, ViewGroup parent) { View selected = item(position); selected.setBackgroundColor(Color.TRANSPARENT); return selected; }
            @Override public View getDropDownView(int position, View convert, ViewGroup parent) { return item(position); }
            private View item(int position) { TextView view = text(getItem(position), 15); view.setBackgroundColor(surface); view.setPadding(dp(8), dp(4), dp(8), dp(4)); view.setMinHeight(dp(48)); return view; }
        };
    }
    private void setPalette() {
        background = Color.parseColor(dark ? "#1D191A" : "#F4EFE9"); surface = Color.parseColor(dark ? "#2A2526" : "#E7E0DA"); foreground = Color.parseColor(dark ? "#F3ECE5" : "#2E2828");
        muted = Color.parseColor(dark ? "#C0B1AA" : "#6F6463"); border = Color.parseColor(dark ? "#54464A" : "#C4B8B2"); cyan = Color.parseColor(dark ? "#7FD9DF" : "#116A77");
        violet = Color.parseColor(dark ? "#C7A7ED" : "#6C4AA1"); red = Color.parseColor(dark ? "#FFB5B9" : "#A4262C"); stopFill = Color.parseColor(dark ? "#89415A" : "#7E2E43");
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView text(String content, int size) { TextView view = new TextView(this); view.setText(content); view.setTextSize(size); view.setTextColor(foreground); view.setTypeface(getResources().getFont(R.font.nunito)); view.setFontVariationSettings("'wght' 500"); return view; }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setTypeface(getResources().getFont(R.font.nunito)); button.setFontVariationSettings("'wght' 600"); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setMinimumHeight(dp(48)); button.setMinHeight(dp(48)); button.setPadding(dp(12), dp(4), dp(12), dp(4));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{muted, foreground}));
        button.setBackground(buttonBackground(Color.TRANSPARENT, Color.TRANSPARENT)); button.setOnClickListener(v -> action.run()); return button;
    }
    private RippleDrawable buttonBackground(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(8)); shape.setStroke(dp(1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), shape, null);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}



