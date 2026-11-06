package dev.prototype.lovense;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SessionGateTest {
    private SessionGate pet() {
        SessionGate gate = new SessionGate(true); assertTrue(gate.state("idle", true, true, false, false)); gate.requestReady();
        assertTrue(gate.state("ready", true, true, true, false)); assertTrue(gate.state("ready", true, true, false, true)); return gate;
    }
    @Test public void stopRejectsOldFramesAndRequiresFreshReadyEpoch() {
        SessionGate gate = pet(); assertTrue(gate.acceptFrame("ready", 1)); gate.stop();
        assertFalse(gate.acceptFrame("ready", 2)); assertFalse(gate.state("ready", true, true, false, true)); assertFalse(gate.active());
        assertTrue(gate.state("paused", true, true, false, false)); assertTrue(gate.mayRequest());
        gate.requestReady(); assertTrue(gate.state("newReady", true, true, true, false)); assertTrue(gate.state("newReady", true, true, false, true));
        assertFalse(gate.acceptFrame("ready", 999)); assertTrue(gate.acceptFrame("newReady", 1));
    }
    @Test public void unauthorizedEpochClearsExistingPermission() { SessionGate gate=pet(); assertFalse(gate.state("unrequested",true,true,false,true)); assertFalse(gate.active()); assertFalse(gate.acceptFrame("ready",3)); }
    @Test public void replayOrDuplicateSequenceCannotMoveControls() { SessionGate gate=pet(); assertTrue(gate.acceptFrame("ready",8)); assertFalse(gate.acceptFrame("ready",8)); assertFalse(gate.acceptFrame("ready",7)); assertTrue(gate.acceptFrame("ready",9)); }
    @Test public void reconnectCannotRestorePermission() { SessionGate gate=pet(); gate.disconnected(); assertFalse(gate.state("ready",true,true,false,true)); assertFalse(gate.active()); }
    @Test public void ownerNeedsExplicitStartForExactEpoch() {
        SessionGate gate = new SessionGate(false); gate.state("ready",true,true,true,false); assertFalse(gate.state("ready",true,true,false,true));
        gate.state("fresh",true,true,true,false); gate.requestStart(); assertTrue(gate.state("fresh",true,true,false,true));
        gate.stop(); assertFalse(gate.state("fresh",true,true,false,true));
    }
    @Test public void stopWhileReadyDisallowsQueuedStartAndEarlyReady() {
        SessionGate gate=new SessionGate(true); gate.state("idle",true,true,false,false); gate.requestReady(); gate.state("ready",true,true,true,false); gate.stop();
        assertFalse(gate.mayRequest()); assertFalse(gate.state("ready",true,true,false,true));
        gate.state("paused",true,true,false,false); assertTrue(gate.mayRequest());
    }
}
