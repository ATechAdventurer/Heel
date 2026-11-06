package dev.prototype.lovense;

/** Local authority. A server message can never restore permission revoked by a local Stop. */
final class SessionGate {
    private final boolean pet;
    private String epoch = "", readyEpoch = "", activeEpoch = "", requestedEpoch = "", blockedEpoch = "";
    private boolean pendingReady, pendingStart, waitingForPause;
    private boolean recoverable;
    private long sequence;
    SessionGate(boolean pet) { this.pet = pet; }
    String epoch() { return epoch; }
    boolean active() { return !activeEpoch.isEmpty(); }
    boolean mayRequest() { return !waitingForPause && !pendingReady && !pendingStart && !epoch.isEmpty(); }
    boolean pending() { return pendingReady || pendingStart; }
    boolean readyForEpoch(String value) { return pet && !readyEpoch.isEmpty() && readyEpoch.equals(value); }
    void requestReady() { if (pet && mayRequest()) { pendingReady = true; requestedEpoch = epoch; } }
    void requestStart() { if (!pet && mayRequest()) { pendingStart = true; requestedEpoch = epoch; } }
    void stop() {
        recoverable = false;
        pause();
    }
    void pause() {
        if (!epoch.isEmpty()) blockedEpoch = epoch;
        readyEpoch = ""; activeEpoch = ""; requestedEpoch = "";
        pendingReady = pendingStart = false; waitingForPause = !epoch.isEmpty(); sequence = 0;
    }
    void disconnected() { pause(); epoch = ""; waitingForPause = false; }
    boolean canRecover() { return recoverable; }
    boolean state(String incoming, boolean paired, boolean online, boolean ready, boolean active, boolean resumed) {
        if (waitingForPause && active) return false;
        if (active && paired && online && !activeEpoch.isEmpty() && incoming.equals(activeEpoch)) return true;
        if (resumed && recoverable && active && paired && online && !incoming.equals(blockedEpoch)) {
            epoch = activeEpoch = incoming; readyEpoch = ""; requestedEpoch = "";
            waitingForPause = pendingReady = pendingStart = false; sequence = 0; return true;
        }
        return state(incoming, paired, online, ready, active);
    }
    /** Returns false when an active state attempted to bypass local readiness. */
    boolean state(String incoming, boolean paired, boolean online, boolean ready, boolean active) {
        if (incoming == null || incoming.isEmpty() || !paired || !online) { disconnected(); epoch = incoming == null ? "" : incoming; return !active; }
        epoch = incoming;
        if (waitingForPause) {
            if (!active && !ready && !incoming.equals(blockedEpoch)) waitingForPause = false;
            else return !active;
        }
        if (!active) {
            activeEpoch = ""; sequence = 0;
            if (pet && ready && pendingReady && !incoming.equals(blockedEpoch)) {
                readyEpoch = incoming; pendingReady = false;
            } else if (!ready) {
                readyEpoch = "";
                if (!incoming.equals(requestedEpoch)) pendingReady = pendingStart = false;
            }
            return true;
        }
        if (incoming.equals(activeEpoch)) return true;
        boolean authorized = pet ? incoming.equals(readyEpoch) && !readyEpoch.isEmpty()
                : pendingStart && incoming.equals(requestedEpoch);
        if (!authorized) { activeEpoch = ""; readyEpoch = ""; pendingReady = pendingStart = false; return false; }
        activeEpoch = incoming; recoverable = true; pendingStart = false; sequence = 0; return true;
    }
    boolean acceptFrame(String incoming, long nextSequence) {
        if (!pet || !active() || !activeEpoch.equals(incoming) || nextSequence <= sequence || nextSequence < 1) return false;
        sequence = nextSequence; return true;
    }
}
