package dev.prototype.lovense;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import okhttp3.*;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Service-owned socket. Retrying a transport never replays a control frame. Main thread only. */
final class RelayClient {
    interface Listener { void onRelay(JSONObject message); void onConnection(boolean connected, String reason); }
    private static RelayClient instance;
    static synchronized RelayClient get(Context context) { if (instance == null) instance = new RelayClient(context.getApplicationContext()); return instance; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CredentialStore store;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build();
    private Listener listener;
    private Listener controller;
    private boolean wanted, retryBlocked;
    private int retries;
    private String pendingStop;
    private WebSocket socket;
    private Call registration;
    private int generation;
    private boolean connected;
    private long lastLease, lastFrame;
    private JSONObject pendingFrame;
    private JSONObject state = json("state", "phase", "unpaired");
    private String connectionReason = "Not connected";
    private RelayClient(Context context) { store = new CredentialStore(context); }
    boolean hasToken() { return store.hasToken(); }
    String endpoint() { return store.endpoint(); }
    boolean connected() { return connected; }
    JSONObject state() { return state; }
    void controller(Listener value) {
        controller = value;
        // Pairing may already own a connected socket before the service/runtime exists.
        // Replay transport + current state through the normal authority checks, never a grant.
        value.onConnection(connected, connectionReason);
        if (connected) value.onRelay(state);
    }
    void attach(Listener value) { listener = value; value.onConnection(connected, connectionReason); if (connected) value.onRelay(state); }
    void detach(Listener value) { if (listener == value) listener = null; }
    void blockRetries(boolean blocked) { retryBlocked = blocked; if (blocked) main.removeCallbacks(reconnect); }
    void networkAvailable() { if (wanted && !retryBlocked && !connected) { main.removeCallbacks(reconnect); main.post(reconnect); } }
    private final Runnable reconnect = () -> { if (wanted && !retryBlocked) connect(null); };
    private void retry() {
        main.removeCallbacks(reconnect);
        if (wanted && !retryBlocked && hasToken()) main.postDelayed(reconnect, new long[]{250,500,1000,2000,4000,5000}[Math.min(retries++,5)]);
    }
    void endpoint(String value) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || !url.encodedPath().equals("/"))
            throw new IllegalArgumentException("Use an HTTPS server address, without a path or password.");
        wanted = false; disconnect("Server changed"); store.endpoint(url.toString().replaceAll("/$", "")); state = json("state", "phase", "unpaired");
    }
    void connect(String enrollment) {
        wanted = true;
        if (connected || socket != null || registration != null) return;
        if (endpoint().isEmpty()) { wanted = false; report(false, "Set your relay address first."); return; }
        final int current = ++generation;
        report(false, "Connecting…");
        try {
            String token = store.token();
            if (token != null) { open(token, current); return; }
        } catch (Exception e) { report(false, "Phone identity unavailable. Use relay settings to reset this phone."); return; }
        if (enrollment == null || enrollment.trim().isEmpty()) { report(false, "Enter this phone's setup code once."); return; }
        Request request = new Request.Builder().url(endpoint() + "/v1/register")
                .header("X-Heel-Enrollment", enrollment.trim()).post(RequestBody.create("", MediaType.get("application/json"))).build();
        registration = http.newCall(request);
        registration.timeout().timeout(15, TimeUnit.SECONDS);
        registration.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { main.post(() -> { if (current != generation) return; registration = null; report(false, "Connection failed. Check the server and internet connection."); }); }
            @Override public void onResponse(Call call, Response response) {
                try (Response result = response) {
                    if (!result.isSuccessful()) throw new IOException("Setup code was not accepted (" + result.code() + ").");
                    String body = result.body().string(); if (body.length() > 4096) throw new IOException("Invalid setup response.");
                    String token = new JSONObject(body).getString("token");
                    main.post(() -> {
                        if (current != generation) return; registration = null;
                        try { store.token(token); if (listener != null) open(token, current); else report(false, "Phone setup complete"); } catch (Exception e) { report(false, "Could not save phone identity. Ask for a replacement setup code."); }
                    });
                } catch (Exception e) { final String message = e instanceof IOException ? e.getMessage() : "Invalid setup response."; main.post(() -> { if (current != generation) return; registration = null; report(false, message); }); }
            }
        });
    }
    private void open(String token, int current) {
        socket = http.newWebSocket(new Request.Builder().url(endpoint() + "/v1/ws").header("Authorization", "Bearer " + token).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { main.post(() -> { if (current != generation) { webSocket.cancel(); return; } lastLease = SystemClock.elapsedRealtime(); main.removeCallbacks(reconnect); report(true, "Connected"); if (pendingStop != null) send(json(pendingStop)); main.post(heartbeat); }); }
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (text.length() > 32768) { webSocket.cancel(); return; }
                final long receivedAt = SystemClock.elapsedRealtime();
                main.post(() -> {
                    if (current != generation || !connected) return;
                    try {
                        JSONObject message = new JSONObject(text); String type = message.getString("type");
                        if (type.equals("frame") && SystemClock.elapsedRealtime() - receivedAt > 500) { disconnect("Control delayed · stopped"); return; }
                        if (type.equals("lease")) { lastLease = SystemClock.elapsedRealtime(); retries = 0; }
                        if (type.equals("state")) {
                            state = message;
                            if (pendingStop != null) {
                                if (message.optString("phase").equals("unpaired") || !message.optBoolean("active") && !message.optBoolean("ready") && (!pendingStop.equals("safeword") || message.optBoolean("safeWord"))) pendingStop = null;
                                else { send(json(pendingStop)); return; }
                            }
                        }
                        if (controller != null) controller.onRelay(message);
                        if (listener != null) listener.onRelay(message);
                    } catch (JSONException e) { disconnect("Invalid relay message · stopped"); }
                });
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) { main.post(() -> { if (current == generation) { boolean rejected = response != null && response.code() == 401; if (rejected) wanted = false; disconnect(rejected ? "Phone identity rejected. Open relay settings." : "Connection lost · reconnecting…"); } }); }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { main.post(() -> { if (current == generation) disconnect("Connection closed · stopped"); }); }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) { webSocket.close(code, null); }
        });
    }
    private final Runnable heartbeat = new Runnable() { @Override public void run() {
        if (!connected) return;
        if (SystemClock.elapsedRealtime() - lastLease > 2000) { disconnect("Connection timed out · stopped"); return; }
        send(json("heartbeat")); main.postDelayed(this, 500);
    }};
    boolean send(JSONObject message) {
        if (!connected || socket == null) return false;
        if (socket.queueSize() > 8192) { disconnect("Connection too slow · stopped"); return false; }
        boolean accepted = socket.send(message.toString());
        if (!accepted) disconnect("Connection lost · stopped");
        return accepted;
    }
    void frame(JSONObject value, boolean priorityZero) {
        pendingFrame = value;
        long now = SystemClock.uptimeMillis();
        if (priorityZero || now - lastFrame >= 33) flushFrame.run();
        else { main.removeCallbacks(flushFrame); main.postDelayed(flushFrame, 33 - (now - lastFrame)); }
    }
    private final Runnable flushFrame = () -> {
        main.removeCallbacks(this.flushFrame); JSONObject next = pendingFrame; pendingFrame = null;
        if (next != null) { lastFrame = SystemClock.uptimeMillis(); send(next); }
    };
    void pause() { pendingFrame = null; main.removeCallbacks(flushFrame); if (pendingStop == null) pendingStop = "pause"; send(json(pendingStop)); }
    void stop() { pendingFrame = null; main.removeCallbacks(flushFrame); if (!"safeword".equals(pendingStop)) pendingStop = "stop"; send(json(pendingStop)); }
    void safeWord() { pendingFrame = null; main.removeCallbacks(flushFrame); pendingStop = "safeword"; blockRetries(true); send(json("safeword")); }
    void clearSafeWordForReady() { pendingStop = null; blockRetries(false); }
    void shutdown() { wanted = false; disconnect("Connection stopped"); }
    void disconnect(String reason) {
        generation++; pendingFrame = null; main.removeCallbacks(flushFrame); main.removeCallbacks(heartbeat);
        if (registration != null) { registration.cancel(); registration = null; }
        WebSocket old = socket; socket = null; if (old != null) old.cancel();
        report(false, reason);
        retry();
    }
    private void report(boolean value, String reason) { connected = value; connectionReason = reason; if (controller != null) controller.onConnection(value, reason); if (listener != null) listener.onConnection(value, reason); }
    static JSONObject json(String type, Object... fields) {
        JSONObject value = new JSONObject(); try { value.put("type", type); for (int i=0;i<fields.length;i+=2) value.put((String)fields[i], fields[i+1]); } catch (JSONException e) { throw new IllegalArgumentException(e); } return value;
    }
}
