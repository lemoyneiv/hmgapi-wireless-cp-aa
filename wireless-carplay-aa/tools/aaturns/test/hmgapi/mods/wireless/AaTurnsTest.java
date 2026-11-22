package hmgapi.mods.wireless;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.util.List;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Host test of AaTurns: verifies both fallback projection-icon mode (standardTurns = false)
 * and default enhanced cluster/AR-HUD mode (standardTurns = true), including:
 *   - Compass restoration (DisplayType 8 + live car GPS heading) & NaviBridge PHONE_TBT IPC
 *   - Clean maneuver-transition distance resets while preserving pre-sent reroute distances
 *   - 0x8006 AaNavState exact maneuver recovery (keep left/right, slight/normal off-ramps,
 *     on-ramp angles, merge side, CW/CCW roundabouts, two-step ROUNDABOUT_ENTER, exit-number
 *     fallbacks, charging-stop duration text, 2nd-step HUD arrow)
 *   - Lane-derived intersection side streets (laneSideStreets)
 *   - Observed-velocity 250 ms approaching-turn countdown (stationary hold, staleness cap)
 */
public final class AaTurnsTest {
    static int fail;
    static Cluster c;

    static void expect(String what, String... want) {
        List<String> got = c.calls;
        String w = String.join("\n", want), g = String.join("\n", got);
        if (w.equals(g)) {
            System.out.println("ok    " + what);
        } else {
            System.out.println("FAIL  " + what + "\n  wanted:\n    " + w.replace("\n", "\n    ") + "\n  got:\n    " + g.replace("\n", "\n    "));
            fail = 1;
        }
        got.clear();
    }

    static String utf16(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(String.format("%02x%02x", ch & 0xff, ch >> 8));
        String r = b.toString();
        while (r.endsWith("00")) r = r.substring(0, r.length() - 2);
        return r;
    }

    // Minimal protobuf writer for synthetic 0x8006 NavigationState dumps
    static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        W v(int f, long x) {
            raw(f << 3);
            raw(x);
            return this;
        }

        W s(int f, String x) {
            try {
                byte[] b = x.getBytes("UTF-8");
                raw((f << 3) | 2);
                raw(b.length);
                o.write(b, 0, b.length);
                return this;
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        W m(int f, W x) {
            byte[] b = x.o.toByteArray();
            raw((f << 3) | 2);
            raw(b.length);
            o.write(b, 0, b.length);
            return this;
        }

        void raw(long x) {
            while ((x & ~0x7FL) != 0) {
                o.write((int) ((x & 0x7F) | 0x80));
                x >>>= 7;
            }
            o.write((int) x);
        }

        String dumpS() {
            byte[] b = o.toByteArray();
            StringBuilder sb = new StringBuilder("S ").append(b.length).append(' ');
            for (byte v : b) sb.append(String.format("%02x", v & 0xff));
            return sb.toString();
        }
    }

    static W step(int exactType, int exitNum, int exitAngle, String text) {
        W m = new W().v(1, exactType);
        if (exitNum >= 0) m.v(2, exitNum);
        if (exitAngle >= 0) m.v(3, exitAngle);
        W st = new W().m(1, m);
        if (text != null) st.m(2, new W().s(1, text));
        return st;
    }

    public static void main(String[] a) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        AaTurns.testNow = -1;
        Car.unit = Car.DISTANCE_MILES;
        c = new Cluster();

        check("standardTurns is enabled by default", new AaTurns(new Context(), c, new Handler()).standardTurns);

        // ---- 1. Fallback projection-icon mode (standardTurns = false)
        Handler h = new Handler();
        Context ctx = new Context();
        AaTurns t = new AaTurns(ctx, c, h);
        t.standardTurns = false;
        final int RIGHT = 2, LEFT = 1, UNSPECIFIED = 3, TURN = 4, MILES = 4, MILES_P1 = 5, METERS = 1, FEET = 6;

        t.nextTurn("Main St", RIGHT, TURN, -1, 0);
        expect("before status(1): cluster untouched while route inactive");
        t.status(1);
        expect("status(1) flushes buffered turn immediately (no 1.3s blank wait)",
                "road [Main St]", "tbt 67 65535 90.0 -1.0 0 0 0 254 -2",
                "hud f201430000" + utf16("Main St"));
        t.nextTurnDistance(320, 30, 200, MILES_P1);
        expect("0.2 mi (basic mode): icon 67, HUD list unchanged, not resent",
                "tbt 67 65535 90.0 0.2 0 0 2 32 0", "2c 2 2 0 0 0 0 0 0 0");
        t.nextTurnDistance(100, 10, 100, METERS);
        expect("100 m with miles set: 328.1 feet", "tbt 67 65535 90.0 328.1 0 0 3 32 0", "2c 3 0 328 0 0 0 0 0 0");
        Car.unit = Car.DISTANCE_KM;
        t.nextTurnDistance(1500, 60, 932, MILES);
        expect("0.9 mi with km set: 1.5 km", "tbt 67 65535 90.0 1.5 0 0 1 32 0", "2c 1 5 1 0 0 0 0 0 0");
        Car.unit = Car.DISTANCE_MILES;

        String lanes = "02" + "01aabbcc" + "02000000";
        t.nextTurn(lanes, 0, 34, 0, 0);
        expect("lanes (count, a free byte, lane types; arrows from byte 19)",
                "lanes f102000102" + "0000000000000000000000000000" + "aabbcc");
        t.nextTurn(lanes, 0, 34, 0, 0);
        expect("same lanes: not resent");
        t.nextTurn("02zz", 0, 34, 0, 0);
        expect("bad lane string: nothing sent");

        t.nextTurn("Oak Ave", LEFT, 64, 270, TURN | 255 << 8);
        t.nextTurnDistance(800, 0, 500, MILES | 0x200);
        t.nextTurnDistance(90, 9, 295000, FEET);
        expect("distance between the turns ahead and their end: HUD list gets the next turn",
                "tbt 67 65535 90.0 295.0 0 0 3 32 0", "hud f202434600" + utf16("Main St"), "2c 3 0 295 0 0 0 0 0 0");
        t.nextTurn(null, 0, 67, 0, 1);
        expect("end of the turns ahead: the cluster's list", "list {1 70 270 [Oak Ave] [] " + (800 / 1609f) + " 2}");
        t.nextTurnDistance(80, 8, 262000, FEET);
        expect("next distance: HUD list already has it, not resent", "tbt 67 65535 90.0 262.0 0 0 3 32 0", "2c 3 0 262 0 0 0 0 0 0");

        t.nextTurnDistance(16093, 1800000000, 0, 0x100 | MILES_P1 | 0x800 | 1 << 12);
        expect("trip: 10.0 mi, arrival 8:00, point 1", "sub 10.0 0 2 8 0 1 1 0.0 -1 1");
        check("unit check scheduled", h.delayed != null);
        h.delayed.run();
        expect("tick, nothing changed: nothing sent");
        Car.unit = Car.DISTANCE_KM;
        h.delayed.run();
        expect("owner switched to km: resent", "sub 16.0 0 1 8 0 1 1 0.0 -1 1");
        Car.unit = Car.DISTANCE_MILES;

        // Deliver a live car heading (90 deg East -> cluster compass 270.0) before route end
        ctx.deliver(new Intent(AaTurns.NAVI_GPS_ACTION).putExtra(AaTurns.NAVI_HEADING_EXTRA, 90));
        t.status(2);
        expect("guidance ends: restores DisplayType 8 (COMPASS) with live heading, clears text/turnList/HUD",
                "dest 0 []", "road []", "list",
                "tbt 8 0 270.0 0.0 0 0 0 0 -1",
                "sub 0.0 -3 15 -1 -1 -1 -1 270.0 0 -1",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        check("unit check stopped", h.delayed == null);
        Intent lastBc = ctx.broadcasts.get(ctx.broadcasts.size() - 1);
        check("PHONE_TBT inactive broadcast sent to com.mnsoft.navi",
                AaTurns.ACTION_PHONE_TBT.equals(lastBc.getAction())
                        && AaTurns.NAVI_PKG.equals(lastBc.getPackage())
                        && !lastBc.getBooleanExtra("active", true)
                        && lastBc.getIntExtra("bar", 0) == -1);

        t.status(1);
        t.nextTurn("Home", 0, 32, 0, 2);
        expect("destination name", "dest 0 [Home]");
        t.nextTurn("Home", 0, 32, 0, 3);
        expect("same destination: not resent");
        check("destination point kept", t.estPoint == 3);

        // ---- 2. Roundabout & fallback icon guarantees (never 64)
        AaTurns d = new AaTurns(new Context(), c, new Handler());
        d.standardTurns = false;
        d.status(1);
        d.nextTurn("Start Rd", 0, 1, 0, 0);
        expect("depart (first): road as destination, no distance yet", "dest 0 [Start Rd]", "tbt 2 65535 0.0 -1.0 0 0 0 254 -2");
        d.nextTurn("Circle", RIGHT, 13, 100, 0);
        expect("roundabout, exit at 100 degrees", "road [Circle]", "tbt 99 65535 90.0 -1.0 0 0 0 254 -2", "hud f201630000" + utf16("Circle"));
        check("maneuver log: event, side, angle, number, what it became",
                "HMG-API [wireless-carplay-aa] AA turn: event 13 side 2 angle 100 number 0 -> type 99 dir 90".equals(android.util.Log.last));
        int logged = android.util.Log.count;
        d.nextTurn("Circle", RIGHT, 13, 100, 0);
        d.nextTurn("Circle", RIGHT, 13, 100, 0);
        check("maneuver log: the same maneuver again isn't logged again", android.util.Log.count == logged);
        c.calls.clear();
        d.nextTurn("Old Rd", 0, 11, 0, 0);
        expect("roundabout enter (basic mode): not for the cluster, preserves roundaboutType");
        d.nextTurn("Old Rd", UNSPECIFIED, 12, -1, 0);
        expect("in the roundabout (exit, no side, no angle): its exit stays on, not 64",
                "road [Old Rd]", "tbt 99 65535 90.0 -1.0 0 0 0 254 -2", "hud f201630000" + utf16("Old Rd"));
        d.nextTurn("Old Rd", LEFT, 12, -1, 0);
        expect("in the roundabout with a side (LEFT, as for a US one): its exit stays on, not 33",
                "road [Old Rd]", "tbt 99 65535 90.0 -1.0 0 0 0 254 -2");
        d.nextTurn("Pine St", RIGHT, TURN, -1, 0);
        expect("after the roundabout: the next turn", "road [Pine St]", "tbt 67 65535 90.0 -1.0 0 0 0 254 -2", "hud f201430000" + utf16("Pine St"));
        d.nextTurn("Ring Rd", UNSPECIFIED, 12, -1, 0);
        expect("roundabout exit with no roundabout before it: straight (65), not 64",
                "road [Ring Rd]", "tbt 65 65535 0.0 -1.0 0 0 0 254 -2", "hud f201410000" + utf16("Ring Rd"));
        d.nextTurn("Loop", RIGHT, 13, -1, 0);
        expect("roundabout with no angle: straight (65), not 64",
                "road [Loop]", "tbt 65 65535 0.0 -1.0 0 0 0 254 -2", "hud f201410000" + utf16("Loop"));
        d.nextTurn("Circle Dr", RIGHT, 64, 100, 13 | 2 << 8);
        d.nextTurnDistance(800, 0, 500, MILES | 0x200);
        d.nextTurn(null, 0, 67, 0, 1);
        expect("a roundabout in the turns ahead: its exit icon and direction (99, 90), not 64",
                "list {1 99 90 [Circle Dr] [2] " + (800 / 1609f) + " 2}");
        d.nextTurn("Hill Rd", RIGHT, 13, 300, 0);
        expect("roundabout, exit at 225: 104 (the car's own icon), not 103",
                "road [Hill Rd]", "tbt 104 65535 225.0 -1.0 0 0 0 254 -2", "hud f201680000" + utf16("Hill Rd"));
        d.nextTurn("Lake Rd", RIGHT, 13, 200, 0);
        expect("roundabout, exit at 315: 107 (the car's own icon), not 106",
                "road [Lake Rd]", "tbt 107 65535 315.0 -1.0 0 0 0 254 -2", "hud f2016b0000" + utf16("Lake Rd"));
        d.nextTurn("Ash St", UNSPECIFIED, TURN, -1, 0);
        expect("turn with no side: straight (65), not 64",
                "road [Ash St]", "tbt 65 65535 0.0 -1.0 0 0 0 254 -2", "hud f201410000" + utf16("Ash St"));
        d.nextTurn("Somewhere", 0, 0, -1, 0);
        expect("unknown maneuver: straight (65), not 64",
                "tbt 65 65535 0.0 -1.0 0 0 0 254 -2", "hud f201410000" + utf16("Somewhere"));
        d.nextTurn("Oak Rd", RIGHT, TURN, -1, 0);
        expect("a right turn before the fork", "road [Oak Rd]", "tbt 67 65535 90.0 -1.0 0 0 0 254 -2", "hud f201430000" + utf16("Oak Rd"));
        d.nextTurn("Split", UNSPECIFIED, 9, -1, 0);
        expect("fork with no side: the plain fork (172), not the last turn's arrow",
                "road [Split]", "tbt 172 65535 0.0 -1.0 0 0 0 254 -2", "hud f201ac0000" + utf16("Split"));
        d.nextTurn("Exit 5", RIGHT, 64, -1, 8 | 255 << 8);
        d.nextTurn("Y", UNSPECIFIED, 65, -1, 9 | 255 << 8);
        d.nextTurn("Home", RIGHT, 66, -1, 19 | 255 << 8);
        d.nextTurnDistance(800, 0, 500, MILES | 0x200);
        d.nextTurnDistance(1600, 0, 1000, MILES | 0x200 | 1 << 10);
        d.nextTurnDistance(3200, 0, 2000, MILES | 0x200 | 2 << 10);
        d.nextTurn(null, 0, 67, 0, 3);
        expect("turns ahead: exit right 81, fork with no side 172, destination on the right 113 (none 64)",
                "list {1 81 0 [Exit 5] [] " + (800 / 1609f) + " 2} {2 172 0 [Y] [] " + (1600 / 1609f) + " 2} {3 113 0 [Home] [] "
                        + (3200 / 1609f) + " 2}");
        check("turns ahead: unknown, merge, depart: straight (65); sided merge 66/71",
                AaTurns.aheadType(0, 0, -1) == 65 && AaTurns.aheadType(10, 0, -1) == 65 && AaTurns.aheadType(1, 0, -1) == 65
                        && AaTurns.aheadType(10, RIGHT, -1) == 66 && AaTurns.aheadType(10, LEFT, -1) == 71);
        check("turns ahead: on-ramp right 173, left 174; ferry 176/176; name change left 70",
                AaTurns.aheadType(7, RIGHT, -1) == 173 && AaTurns.aheadType(7, LEFT, -1) == 174
                        && AaTurns.aheadType(16, 0, -1) == 176 && AaTurns.aheadType(17, 0, -1) == 176
                        && AaTurns.aheadType(2, LEFT, -1) == 70);
        d.stop();
        d.end();
        expect("connection ends during a route: restores DisplayType 8 (COMPASS) and clears HUD",
                "dest 0 []", "road []", "list",
                "tbt 8 0 0.0 0.0 0 0 0 0 -1",
                "sub 0.0 -3 15 -1 -1 -1 -1 0.0 0 -1",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        d.nextTurn("S 3 0a0102", 0, 33, 0, 0);
        expect("navigation message: nothing to the cluster");
        check("... passed on to mods that asked (projection events)", "S 3 0a0102".equals(hmgapi.hook.ProjectionHooks.last));

        // ---- 3. Default enhanced mode (standardTurns = true) & clean maneuver distance resets
        Context sCtx = new Context();
        AaTurns s = new AaTurns(sCtx, c, new Handler());
        s.status(1);
        s.nextTurn("Elm St", LEFT, TURN, -1, 0);
        c.calls.clear();
        s.nextTurnDistance(1200, 90, 700, MILES_P1);
        expect("standard: 0.7 mi out: the icon (70 left), bar off", "tbt 70 65535 270.0 0.7 0 0 2 0 0", "2c 2 7 0 0 0 0 0 0 0");
        s.nextTurnDistance(440, 40, 273, MILES_P1);
        expect("standard: 440 m: TURN (13) 270, tenth-rounded 0.3 mi, bar 90; HUD keeps its icon", "tbt 13 0 270.0 0.3 0 0 2 90 0", "2c 2 3 0 0 0 0 0 0 0");
        Intent bar90 = sCtx.broadcasts.get(sCtx.broadcasts.size() - 1);
        check("PHONE_TBT broadcast carries trap bar 90", bar90.getBooleanExtra("active", false) && bar90.getIntExtra("bar", -1) == 90);
        s.nextTurnDistance(274, 25, 900000, FEET);
        expect("standard: 900 ft (274 m): bar 50", "tbt 13 0 270.0 900.0 0 0 3 50 0", "2c 3 0 900 0 0 0 0 0 0");
        s.nextTurnDistance(9, 1, 29529, FEET);
        expect("standard: 29 ft: bar 10 (at least 1)", "tbt 13 0 270.0 29.529 0 0 3 10 0", "2c 3 0 29 0 0 0 0 0 0");

        // Advancing to a new maneuver ("Hwy 9") clears the completed turn's 29 ft so it never flashes 29 ft
        s.nextTurn("Hwy 9", 0, 14, -1, 0);
        check("maneuver log, standard turns: also what goes out near it",
                "HMG-API [wireless-carplay-aa] AA turn: event 14 side 0 angle -1 number 0 -> type 65 dir 0, near: type 13 dir 0".equals(android.util.Log.last));
        expect("new maneuver clears completed turn's 29 ft until new distance arrives",
                "road [Hwy 9]", "tbt 65 65535 0.0 -1.0 0 0 0 254 -2", "hud f201410000" + utf16("Hwy 9"));
        s.nextTurnDistance(9, 1, 29529, FEET);
        expect("standard: straight near once distance arrives: TURN 0",
                "tbt 13 0 0.0 29.529 0 0 3 10 0", "2c 3 0 29 0 0 0 0 0 0");

        final int SLIGHT = 3, SHARP = 5, UTURN = 6, ON_RAMP = 7, OFF_RAMP = 8, FORK = 9, MERGE = 10, RB_EXIT = 12, RB_BOTH = 13, DEST = 19, FERRY = 16;
        stdCase("right turn", TURN, RIGHT, 67, 90, 100, "13 90 20");
        stdCase("slight right: 45 (the car's), not the icon's 30", SLIGHT, RIGHT, 66, 30, 100, "13 45 20");
        stdCase("slight left: 315", SLIGHT, LEFT, 71, 330, 100, "13 315 20");
        stdCase("sharp right: 135", SHARP, RIGHT, 68, 135, 100, "13 135 20");
        stdCase("sharp left: 225", SHARP, LEFT, 69, 225, 100, "13 225 20");
        stdCase("U-turn (left): U_TURN 270", UTURN, LEFT, 72, 270, 100, "31 270 20");
        stdCase("fork right: FORK_2 90", FORK, RIGHT, 173, 0, 100, "25 90 20");
        stdCase("exit right: EXIT_RIGHT 90", OFF_RAMP, RIGHT, 81, 30, 300, "17 90 60");
        stdCase("exit left: EXIT_LEFT 270", OFF_RAMP, LEFT, 82, 330, 300, "18 270 60");
        stdCase("merge: TURN 0", MERGE, 0, 65, 0, 100, "13 0 20");
        stdCase("merge right: TURN 45", MERGE, RIGHT, 66, 45, 100, "13 45 20");
        stdCase("merge left: TURN 315", MERGE, LEFT, 71, 315, 100, "13 315 20");
        stdCase("roundabout, exit at 90: roundabout (32), the exit's direction", RB_BOTH, RIGHT, 99, 90, 200, "32 90 40");
        stdCase("roundabout marked LEFT (a US one, issue #19): 32, counterclockwise, not 33", RB_EXIT, LEFT, 99, 90, 200, "32 90 40");
        stdCase("roundabout marked RIGHT: 32", RB_BOTH, RIGHT, 105, 270, 200, "32 270 40");
        stdCase("a clockwise icon (192-203): 33", RB_BOTH, 0, 195, 90, 200, "33 90 40");
        stdCase("on-ramp right: TURN 45 (the car's turn point; the AR HUD animates it), not the icon", ON_RAMP, RIGHT, 173, 30, 200, "13 45 40");
        stdCase("on-ramp left: TURN 315", ON_RAMP, LEFT, 174, 330, 200, "13 315 40");
        stdCase("on-ramp without a side: TURN 0", ON_RAMP, 0, 172, 0, 200, "13 0 40");
        stdCase("on-ramp far out: the icon, bar off", ON_RAMP, RIGHT, 173, 30, 600, "173 30 0");
        stdCase("ferry: the icon, with the bar", FERRY, 0, 176, 0, 200, "176 0 40");
        stdCase("turn without a side: the icon (straight), with the bar", TURN, 0, 65, 0, 200, "65 0 40");
        stdCase("fork without a side: the icon (172), with the bar", FORK, 0, 172, 0, 200, "172 0 40");
        stdCase("destination on the right: 113, no bar", DEST, RIGHT, 3, 0, 100, "113 0 0");
        stdCase("destination on the left: 114", DEST, LEFT, 3, 0, 100, "114 0 0");
        stdCase("destination ahead: 112, also far out", DEST, 0, 3, 0, 5000, "112 0 0");
        stdCase("destination within 20 m: ARRIVED (3)", DEST, RIGHT, 3, 0, 19, "3 0 0");
        stdCase("457 m: in, bar 90", TURN, RIGHT, 67, 90, 457, "13 90 90");
        stdCase("458 m: out, the icon, bar off", TURN, RIGHT, 67, 90, 458, "67 90 0");
        stdCase("no distance yet: the icon, bar off", TURN, RIGHT, 67, 90, -1, "67 90 0");

        // ---- 4. Rerouting (status 3): "Recalculating..." (10) after 1 s, preserving pre-sent reroute distance
        Handler rh = new Handler();
        AaTurns rr = new AaTurns(new Context(), c, rh);
        rr.status(1);
        rr.nextTurn("Elm St", RIGHT, TURN, -1, 0);
        c.calls.clear();
        rr.status(3);
        expect("reroute: nothing at once");
        check("reroute: Recalculating waits 1 s", rh.delayed == rr.rerouteShow);
        rr.status(1);
        check("a short reroute: nothing shown, the wait cancelled", rh.delayed == null && c.calls.isEmpty());
        rr.status(3);
        rh.delayed.run();
        expect("reroute over 1 s: Recalculating (10)", "tbt 10 65535 0.0 0.0 -3 0 15 0 -1");
        rr.nextTurnDistance(300, 20, 186, MILES_P1);
        expect("a distance during the reroute: Recalculating stays");
        rh.delayed = null;
        rr.status(3);
        check("rerouting again while it shows: no second wait", rh.delayed == null);
        rr.nextTurn("Oak Ave", LEFT, TURN, -1, 0);
        expect("the new route's first turn replaces it with the pre-sent reroute distance",
                "road [Oak Ave]", "tbt 13 0 270.0 0.2 0 0 2 60 0",
                "hud f201460000" + utf16("Oak Ave"), "2c 2 2 0 0 0 0 0 0 0");
        rr.status(3);
        rh.delayed.run();
        c.calls.clear();
        rr.status(1);
        expect("reroute over with the same route: the turn comes back",
                "tbt 13 0 270.0 0.2 0 0 2 60 0", "2c 2 2 0 0 0 0 0 0 0");
        Handler oh = new Handler();
        AaTurns off = new AaTurns(new Context(), c, oh);
        off.standardTurns = false;
        off.status(1);
        off.status(3);
        check("standard turns off: no Recalculating", oh.delayed == null);

        // ---- 5. 0x8006 AaNavState exact maneuvers, charging stops, roundabouts & lane side streets
        testExactManeuversAndLanes();

        // ---- 6. Anti-oscillation (.4->.3->.2->.1->ft), short-unit 250 ms countdown & compass quantization
        testSmoothAndCompass();

        System.exit(fail);
    }

    static void testExactManeuversAndLanes() {
        final int LEFT = 1, RIGHT = 2, UNSPECIFIED = 3, SLIGHT = 3, TURN = 4, ON_RAMP = 7, OFF_RAMP = 8, MERGE = 10;
        final int FEET = 6, MILES_P1 = 5;
        AaTurns x = new AaTurns(new Context(), c, new Handler());
        x.status(1);
        c.calls.clear();

        // KEEP_LEFT (55, collapsed by GPM to SLIGHT_TURN LEFT) -> fork icon 174 far, FORK_2 (25) 270 near
        W dumpKeep = new W().m(1, step(AaNavState.M_KEEP_LEFT, -1, -1, "I-5 N"))
                .m(1, step(AaNavState.M_OFF_RAMP_SLIGHT_RIGHT, -1, -1, "Exit 12"));
        x.nextTurn(dumpKeep.dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        x.nextTurn("I-5 N", LEFT, SLIGHT, -1, 0);
        x.nextTurnDistance(800, 30, 500, MILES_P1);
        expect("0x8006 KEEP_LEFT far: fork left 174 + 2nd-step HUD exit right 81 (0x51)",
                "road [I-5 N]", "tbt 174 65535 0.0 -1.0 0 0 0 254 -2",
                "hud f202ae5100" + utf16("I-5 N"),
                "tbt 174 65535 0.0 0.5 0 0 2 0 0", "2c 2 5 0 0 0 0 0 0 0");
        x.nextTurnDistance(200, 10, 124, MILES_P1);
        expect("0x8006 KEEP_LEFT near: FORK_2 (25) 270 instead of intersection slight-left TURN",
                "tbt 25 0 270.0 0.1 0 0 2 40 0", "2c 2 1 0 0 0 0 0 0 0");

        // EV_AHEAD_END with 0 clears stale 2nd-step HUD icon when rawNextIcon is also cleared
        W singleStep = new W().m(1, step(AaNavState.M_OFF_RAMP_SLIGHT_RIGHT, -1, -1, "Exit 12"));
        x.nextTurn(singleStep.dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        x.nextTurn("Exit 12", UNSPECIFIED, OFF_RAMP, -1, 0);
        x.nextTurnDistance(300, 12, 186, MILES_P1);
        expect("0x8006 OFF_RAMP_SLIGHT_RIGHT recovers RIGHT side when legacy side is UNSPECIFIED -> EXIT_RIGHT (17)",
                "road [Exit 12]", "tbt 81 65535 30.0 -1.0 0 0 0 254 -2",
                "hud f201510000" + utf16("Exit 12"),
                "tbt 17 0 90.0 0.2 0 0 2 60 0", "2c 2 2 0 0 0 0 0 0 0");

        // ON_RAMP_SHARP_LEFT -> 225 deg near; MERGE_RIGHT -> 66 (45 deg)
        x.nextTurn(new W().m(1, step(AaNavState.M_ON_RAMP_SHARP_LEFT, -1, -1, "US-101")).dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        x.nextTurn("US-101", LEFT, ON_RAMP, -1, 0);
        x.nextTurnDistance(150, 8, 492000, FEET);
        expect("0x8006 ON_RAMP_SHARP_LEFT near: TURN 225",
                "road [US-101]", "tbt 174 65535 330.0 -1.0 0 0 0 254 -2",
                "hud f201ae0000" + utf16("US-101"),
                "tbt 13 0 225.0 492.0 0 0 3 30 0", "2c 3 0 492 0 0 0 0 0 0");

        x.nextTurn(new W().m(1, step(AaNavState.M_MERGE_RIGHT, -1, -1, "I-90")).dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        x.nextTurn("I-90", UNSPECIFIED, MERGE, -1, 0);
        x.nextTurnDistance(200, 8, 124, MILES_P1);
        expect("0x8006 MERGE_RIGHT: icon 66 on HUD, TURN 45 near",
                "road [I-90]", "tbt 66 65535 45.0 -1.0 0 0 0 254 -2",
                "hud f201420000" + utf16("I-90"),
                "tbt 13 0 45.0 0.1 0 0 2 40 0", "2c 2 1 0 0 0 0 0 0 0");

        // Clockwise (LHT) roundabout + "At the roundabout, " prefix stripping + exit preservation
        x.nextTurn(new W().m(1, step(AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 2, 90, "High St")).dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        x.nextTurn("At the roundabout, take the 2nd exit", LEFT, 13, 100, 2);
        x.nextTurnDistance(200, 10, 124, MILES_P1);
        expect("0x8006 CW roundabout: prefix stripped, clockwise icon 195 (0xc3) on HUD, TYPE_ROUNDABOUT_LEFT (33) near",
                "road [Take the 2nd exit]", "tbt 195 65535 90.0 -1.0 0 0 0 254 -2",
                "hud f201c30000" + utf16("Take the 2nd exit"),
                "tbt 33 0 90.0 0.1 0 0 2 40 0", "2c 2 1 0 0 0 0 0 0 0");
        x.nextTurn("High St", UNSPECIFIED, 12, -1, 0);
        x.nextTurnDistance(40, 4, 131000, FEET);
        expect("CW roundabout preserved into ROUNDABOUT_EXIT: stays TYPE_ROUNDABOUT_LEFT (33) 90",
                "road [High St]", "tbt 195 65535 90.0 -1.0 0 0 0 254 -2",
                "hud f201c30000" + utf16("High St"),
                "tbt 33 0 90.0 131.0 0 0 3 10 0", "2c 3 0 131 0 0 0 0 0 0");

        // Standalone two-step ROUNDABOUT_ENTER looking ahead to step 1's exitNumber=3 -> 105 (270 deg)
        W twoStepRb = new W().m(1, step(AaNavState.M_ROUNDABOUT_ENTER_CCW, -1, -1, "Dupont Cir"))
                .m(1, step(AaNavState.M_ROUNDABOUT_EXIT_CCW, 3, -1, "Mass Ave"));
        x.nextTurn("Bridge St", RIGHT, TURN, -1, 0); // leave previous roundabout first
        c.calls.clear();
        x.nextTurn(twoStepRb.dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        x.nextTurn("Dupont Cir", 0, 11, 0, 0);
        x.nextTurnDistance(150, 9, 492000, FEET);
        expect("standalone ROUNDABOUT_ENTER uses step[1] exitNumber=3 -> 105 / 270 deg",
                "road [Dupont Cir]", "tbt 105 65535 270.0 -1.0 0 0 0 254 -2",
                "hud f202696900" + utf16("Dupont Cir"),
                "tbt 32 0 270.0 492.0 0 0 3 30 0", "2c 3 0 492 0 0 0 0 0 0");

        // Charging-stop destination formatting (" - 18 min charge")
        x.nextTurn("Ionity", 0, AaTurns.EV_DEST_NAME, 0, 1);
        expect("plain destination name before charging dump", "dest 0 [Ionity]");
        W chg = new W().m(2, new W().s(1, "Ionity").m(2, new W().v(1, 15000).v(2, 150000).v(3, 1080)));
        x.nextTurn(chg.dumpS(), 0, AaTurns.EV_DUMP, 0, 0);
        expect("charging stop destination updated with planned charge minutes", "dest 0 [Ionity - 18 min charge]");

        // Lane-derived intersection side streets (straight=1, right=3, left=7 -> 4-way crossroad 0x811 = 2065)
        x.nextTurn("2nd Ave", RIGHT, TURN, -1, 0);
        x.nextTurnDistance(120, 8, 394000, FEET);
        c.calls.clear();
        String crossLanes = "03" + "01070000" + "01010000" + "02030000";
        x.nextTurn(crossLanes, 0, AaTurns.EV_LANES, 0, 0);
        check("laneSideStreets: 4-way crossroad (left 270 + straight 0 + right 90) -> 0x811 (2065)",
                AaTurns.laneSideStreets(90, new int[] { 7, 1, 3 }) == 0x811
                        && AaTurns.laneSideStreets(90, new int[] { 3 }) == 0
                        && AaTurns.laneSideStreets(90, new int[] { 7, 5 }) == 0);
        expect("near TURN re-emits tbtInfo with lane-derived sideStreets 2065 (0x811)",
                "lanes f10300010102" + "00000000000000000000000000" + "07000001000003",
                "tbt 13 2065 90.0 394.0 0 0 3 20 0", "2c 3 0 394 0 0 0 0 0 0");
    }

    static void testSmoothAndCompass() {
        check("clusterCompassDeg quantizes counter-clockwise 22.5 deg steps",
                AaTurns.clusterCompassDeg(0) == 0f
                        && AaTurns.clusterCompassDeg(90) == 270f
                        && AaTurns.clusterCompassDeg(180) == 180f
                        && AaTurns.clusterCompassDeg(270) == 90f
                        && AaTurns.clusterCompassDeg(30) == 337.5f);

        AaTurns.testNow = 1000L;
        Handler sh = new Handler();
        AaTurns sm = new AaTurns(new Context(), c, sh);
        sm.status(1);
        sm.nextTurn("Market St", 2, 4, -1, 0);
        c.calls.clear();

        // Part A: .4 -> .3 -> .2 -> .1 -> ft anti-oscillation regression (no 250 ms sub-tenth MI ticks, monotonic clamp)
        sm.nextTurnDistance(610, 25, 400, 5); // real AA: 610 m (0.379 mi) rounded to "0.4"
        expect("0.4 mi (610 m): steady 0.4 mi", "tbt 67 65535 90.0 0.4 0 0 2 0 0", "2c 2 4 0 0 0 0 0 0 0");
        check("UNIT_MI does not schedule 250 ms sub-tenth interpolation (prevents .4 <-> .3 flip)",
                !sh.scheduled.containsKey(sm.smooth));

        AaTurns.testNow = 2000L;
        sm.nextTurnDistance(520, 21, 300, 5);
        expect("drops cleanly to 0.3 mi", "tbt 67 65535 90.0 0.3 0 0 2 0 0", "2c 2 3 0 0 0 0 0 0 0");

        AaTurns.testNow = 3000L;
        sm.nextTurnDistance(570, 23, 400, 5); // GPS boundary wobble back across .35 mi
        expect("wobble back toward 0.4 mi is clamped steady at 0.3 mi",
                "tbt 67 65535 90.0 0.3 0 0 2 0 0", "2c 2 3 0 0 0 0 0 0 0");

        AaTurns.testNow = 4000L;
        sm.nextTurnDistance(380, 16, 200, 5); // enters GUIDE_WINDOW at 0.2 mi, bar 80
        expect("0.2 mi inside GUIDE_WINDOW: TURN 90, bar 80",
                "tbt 13 0 90.0 0.2 0 0 2 80 0", "2c 2 2 0 0 0 0 0 0 0");
        check("UNIT_MI inside GUIDE_WINDOW still suppresses 250 ms sub-tenth interpolation",
                !sh.scheduled.containsKey(sm.smooth));

        AaTurns.testNow = 5000L;
        sm.nextTurnDistance(415, 18, 300, 5); // wobble toward 0.3 mi / bar 90
        expect("wobble inside GUIDE_WINDOW stays clamped at 0.2 mi and bar 80",
                "tbt 13 0 90.0 0.2 0 0 2 80 0", "2c 2 2 0 0 0 0 0 0 0");

        AaTurns.testNow = 6000L;
        sm.nextTurnDistance(220, 10, 100, 5);
        expect("drops cleanly to 0.1 mi, bar 40",
                "tbt 13 0 90.0 0.1 0 0 2 40 0", "2c 2 1 0 0 0 0 0 0 0");

        AaTurns.testNow = 7000L;
        sm.nextTurnDistance(250, 11, 200, 5); // .2/.1 boundary wobble
        expect(".2/.1 wobble stays clamped at 0.1 mi, bar 40",
                "tbt 13 0 90.0 0.1 0 0 2 40 0", "2c 2 1 0 0 0 0 0 0 0");

        AaTurns.testNow = 8000L;
        sm.nextTurnDistance(150, 8, 500000, 6); // crosses into UNIT_FT (500 ft)
        expect("transitions into 500 ft, bar 30",
                "tbt 13 0 90.0 500.0 0 0 3 30 0", "2c 3 0 500 0 0 0 0 0 0");
        check("UNIT_FT inside GUIDE_WINDOW schedules 250 ms smooth countdown",
                sh.scheduled.containsKey(sm.smooth));

        AaTurns.testNow = 9000L;
        sm.nextTurnDistance(162, 9, 100, 5); // boundary bounce back to 0.1 mi
        expect("FT -> MI bounce stays locked in feet (500.0 ft, bar 30)",
                "tbt 13 0 90.0 500.0 0 0 3 30 0", "2c 3 0 500 0 0 0 0 0 0");

        // Part B: Short-unit (UNIT_FT) 250 ms glide in 10-ft steps, seconds==0 continuation, red-light clamp & wrong-way unlock
        sm.nextTurn("Oak St", 2, 4, -1, 0);
        c.calls.clear();

        AaTurns.testNow = 10000L;
        sm.nextTurnDistance(122, 6, 400000, 6); // 122 m (400 ft), 6 s -> v = 20.33 m/s
        expect("short-unit t=0: 400 ft, bar 20",
                "tbt 13 0 90.0 400.0 0 0 3 20 0", "2c 3 0 400 0 0 0 0 0 0");
        check("short-unit smooth 250 ms tick scheduled", sh.scheduled.containsKey(sm.smooth));

        AaTurns.testNow = 10500L;
        sm.smooth.run();
        expect("short-unit t=500ms: glides down in 10-ft steps to 370 ft",
                "tbt 13 0 90.0 370.0 0 0 3 20 0", "2c 3 0 370 0 0 0 0 0 0");

        // Second raw report at t=11000ms: 98 m (320 ft), seconds=0 (AA hits 0s early) -> observed v = 24 m/s continues!
        AaTurns.testNow = 11000L;
        sm.nextTurnDistance(98, 0, 320000, 6);
        c.calls.clear();
        AaTurns.testNow = 11500L;
        sm.smooth.run();
        expect("smooth continues from observed velocity when seconds == 0 (280 ft, bar drops to 10)",
                "tbt 13 0 90.0 280.0 0 0 3 10 0", "2c 3 0 280 0 0 0 0 0 0");

        // Red-light stop with slight GPS upward drift (100 m / 330 ft): pauses smooth AND clamps steady at 280 ft!
        AaTurns.testNow = 12500L;
        sm.nextTurnDistance(100, 5, 330000, 6);
        expect("red-light stop with GPS drift (330 ft after 280 ft): clamped steady at 280 ft, bar 10",
                "tbt 13 0 90.0 280.0 0 0 3 10 0", "2c 3 0 280 0 0 0 0 0 0");
        check("stationary at red light (dm <= 0): smooth countdown pauses instead of drifting",
                !sh.scheduled.containsKey(sm.smooth));

        // Resume motion at t=13500ms (80 m / 260 ft -> v = 20 m/s), then phone goes silent past 1750 ms -> holds!
        AaTurns.testNow = 13500L;
        sm.nextTurnDistance(80, 4, 260000, 6);
        c.calls.clear();
        AaTurns.testNow = 15500L; // dt = 2000 ms > SMOOTH_STALE_MS (1750 ms)
        sm.smooth.run();
        expect("phone silent > 1750 ms: holds last displayed distance without running past turn");

        // Genuine wrong-way travel (+120 m > 60 m) unlocks monotonic clamp cleanly
        AaTurns.testNow = 16500L;
        sm.nextTurnDistance(200, 10, 100, 5);
        expect("genuine wrong-way travel (> 60 m) unlocks back to 0.1 mi, bar 40",
                "tbt 13 0 90.0 0.1 0 0 2 40 0", "2c 2 1 0 0 0 0 0 0 0");

        AaTurns.testNow = -1;
    }

    static void stdCase(String what, int ev, int side, int icon, int iconDir, int m, String want) {
        int[] r = AaTurns.standard(ev, side, icon, iconDir, m);
        String got = r[0] + " " + r[1] + " " + r[2];
        check("standard: " + what + (got.equals(want) ? "" : " (got " + got + ", wanted " + want + ")"), got.equals(want));
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }
}
