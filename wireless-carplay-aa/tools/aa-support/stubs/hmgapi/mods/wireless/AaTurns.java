package hmgapi.mods.wireless;

/** Records the actual hook dispatcher; AaTurns' cluster behavior has its own test suite. */
final class AaTurns {
    static final java.util.List<AaTurns> instances = new java.util.ArrayList<AaTurns>();
    final java.util.List<String> calls = new java.util.ArrayList<String>();
    boolean standardTurns = true;
    long unitCacheMs;
    AaTurns(android.content.Context c, hmgapi.hook.Cluster cluster, android.os.Handler h) { instances.add(this); }
    void nextTurn(String road, int side, int event, int angle, int number) {
        calls.add("turn " + road + " " + side + " " + event + " " + angle + " " + number);
    }
    void nextTurnDistance(int meters, int seconds, int e3, int flags) {
        calls.add("distance " + meters + " " + seconds + " " + e3 + " " + flags);
    }
    void status(int status) { calls.add("status " + status); }
    void stop() { calls.add("stop"); }
    void end() { calls.add("end"); }
}
