package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;

import com.tcc.iap2.Iap2NotiMsg;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Host test of CpTurns: verifies both fallback projection-icon mode (standardTurns = false)
 * and default enhanced cluster/AR-HUD mode (standardTurns = true), including:
 *   - Angle-bucketed turns, highway exits/forks, RHT & LHT roundabouts, exit-count fallbacks,
 *     and partial-update junction geometry preservation
 *   - Intersection side streets from iAP2 junction road angles (items 10 & 11)
 *   - Stock composite multi-arrow 0xF1 lane glyphs + per-part highlight masks & deduplication
 *   - Missing nextStr fallback to u.distNext (never inventing "0 ft"), parked-start late
 *     maneuver rendering, stale-turn clearing, and multi-component state=0 isolation
 *   - Observed-velocity 250 ms approaching-turn countdown
 *   - Compass restoration (DisplayType 8 + live car GPS heading) & NaviBridge PHONE_TBT IPC
 */
public final class CpTurnsTest {
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

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static String utf16(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(String.format("%02x%02x", ch & 0xff, ch >> 8));
        String r = b.toString();
        while (r.endsWith("00")) r = r.substring(0, r.length() - 2);
        return r;
    }

    /** iAP2 types: 1 int8/int16, 5 uint16, 6 uint32, 7 uint64, 8 enum, 10 string, 13 groups */
    static final int S16 = 1, U8 = 1, U16 = 5, U32 = 6, U64 = 7, ENUM = 8, STR = 10, GROUPS = 13;

    static Iap2NotiMsg msg(Object... items) {
        Iap2NotiMsg m = new Iap2NotiMsg();
        for (int i = 0; i < items.length; i += 3) m.getItemGroup().add((Integer) items[i], (Integer) items[i + 1], items[i + 2]);
        return m;
    }

    static int[] i(int... v) {
        return v;
    }

    static long[] l(long... v) {
        return v;
    }

    static String[] s(String v) {
        return new String[] { v };
    }

    /** a RouteGuidanceManeuverUpdate for component 1 */
    static Iap2NotiMsg maneuver(int index, int type, int side, int junction, int exit, String after, int distance) {
        return msg(0, U16, i(1), 1, U16, i(index), 3, ENUM, i(type), 4, STR, s(after), 5, U32, i(distance),
                8, ENUM, i(side), 9, ENUM, i(junction), 11, U8, i(exit));
    }

    /** Synthetic fixtures for India LHT & angle-bucketed turns */
    static void indiaAndAngleBucketTurns() {
        check("India Apple Maps left/right and straight", CpTurns.icon(1, 1, 0, -90, 1) == 70
                && CpTurns.icon(2, 1, 0, 90, 1) == 67 && CpTurns.icon(3, 1, 0, 0, 1) == 65);
        for (int side = 0; side <= 1; side++) {
            check("explicit ordinary turns without optional angle, side " + side,
                    CpTurns.icon(1, side, 0, 0, 1) == 70 && CpTurns.icon(2, side, 0, 0, 1) == 67);
            check("departure is NO_INFO, not a straight arrow, side " + side,
                    CpTurns.icon(11, side, 0, 0, 1) == 1);
        }
        check("invalid traffic sides stay unsupported",
                CpTurns.icon(1, 2, 0, 0, 1) == 0 && CpTurns.icon(2, -1, 0, 0, 1) == 0
                        && CpTurns.icon(3, 2, 0, 0, 1) == 0 && CpTurns.icon(11, 2, 0, 0, 1) == 0);
        check("non-exact real-world angles bucket cleanly (-91 -> 70, +91 -> 67, -35 -> 71, +145 -> 68)",
                CpTurns.icon(1, 1, 0, -91, 1) == 70 && CpTurns.icon(2, 1, 0, 91, 1) == 67
                        && CpTurns.icon(1, 0, 0, -35, 1) == 71 && CpTurns.icon(2, 0, 0, 145, 1) == 68);
        check("contradictory angles/junctions on turns and departure stay unsupported",
                CpTurns.icon(1, 0, 0, 45, 1) == 0 && CpTurns.icon(2, 0, 0, -45, 1) == 0
                        && CpTurns.icon(11, 1, 0, 90, 1) == 0 && CpTurns.icon(11, 1, 1, 0, 1) == 0);

        c = new Cluster();
        Context ctx = new Context();
        CpTurns t = new CpTurns(ctx, c, null);
        t.maneuver(maneuver(0, 2, 1, 0, 90, "Test Road", 100));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("100"),
                12, ENUM, i(2), 13, U16, i(0)));
        expect("India Apple right turn (100 m, default enhanced mode): TURN 90, bar 20, HUD icon 67",
                "road [Test Road]", "tbt 13 0 90.0 100.0 0 0 0 20 0",
                "hud f201430000" + utf16("Test Road"), "2c 0 0 100 0 0 0 0 0 0");
        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 3, ENUM, i(11)));
        t.routeGuidance(msg(0, U16, i(1)));
        expect("Google departure clears prior HUD turn and sends non-directional NO_INFO",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2",
                "road [Test Road]", "tbt 1 0 0.0 100.0 0 0 0 0 0", "2c 0 0 100 0 0 0 0 0 0");
        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 3, ENUM, i(2)));
        t.routeGuidance(msg(0, U16, i(1)));
        expect("Google right without angle reaches cluster and HUD after departure",
                "road [Test Road]", "tbt 13 0 90.0 100.0 0 0 0 20 0",
                "hud f201430000" + utf16("Test Road"), "2c 0 0 100 0 0 0 0 0 0");
        ctx.deliver(new Intent(CpTurns.NAVI_GPS_ACTION).putExtra(CpTurns.NAVI_HEADING_EXTRA, 180));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(0)));
        expect("India route stop restores DisplayType 8 (COMPASS) with live 180 deg heading and clears HUD",
                "dest 0 []", "road []", "list",
                "tbt 8 0 180.0 0.0 0 0 0 0 -1",
                "sub 0.0 -3 15 -1 -1 -1 -1 180.0 0 -1",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        Intent lastBc = ctx.broadcasts.get(ctx.broadcasts.size() - 1);
        check("PHONE_TBT inactive broadcast sent from CpTurns to com.mnsoft.navi",
                CpTurns.ACTION_PHONE_TBT.equals(lastBc.getAction())
                        && CpTurns.NAVI_PKG.equals(lastBc.getPackage())
                        && !lastBc.getBooleanExtra("active", true));
    }

    public static void main(String[] a) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        CpTurns.testNow = -1;
        Car.unit = Car.DISTANCE_MILES;
        c = new Cluster();

        CpTurns def = new CpTurns(new Context(), new Cluster(), null);
        check("standardTurns and sideStreets are enabled by default", def.standardTurns && def.sideStreets);

        // ---- 1. Fallback projection-icon mode (standardTurns = false) end-to-end drive
        final CountDownLatch restarted = new CountDownLatch(1);
        CpTurns t = new CpTurns(new Context(), c, new Runnable() {
            @Override
            public void run() {
                restarted.countDown();
            }
        });
        t.standardTurns = false;
        final int RIGHT = 2, LEFT = 1, STRAIGHT = 3, UTURN = 4, ARRIVE = 12, MILES = 1, KM = 0, FEET = 4;

        t.maneuver(maneuver(0, RIGHT, 0, 0, 90, "Main St", 300));
        t.maneuver(maneuver(1, LEFT, 0, 0, -90, "Oak Ave", 800));
        t.maneuver(maneuver(2, STRAIGHT, 0, 0, 0, "Elm St", 0));
        t.maneuver(maneuver(3, UTURN, 0, 0, 0, "Pine St", 2000));
        expect("maneuvers alone: nothing sent");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 7, U32, i(5000), 9, ENUM, i(MILES), 11, STR, s("0.2"),
                12, ENUM, i(MILES), 13, U16, i(0, 1, 2, 3), 5, U64, l(1700000000L), 19, STR, s("Maps")));
        expect("right turn in 0.2 mi (basic mode): crossroad, turn, HUD list, HUD distance, trip, turns ahead",
                "road [Main St]", "tbt 67 0 0.0 0.2 0 0 2 0 0", "hud f202434600" + utf16("Main St"), "2c 2 2 0 0 0 0 0 0 0",
                "sub 3.1 0 2 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.1")));
        expect("partial update (distance only): same HUD list not resent",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 3.1 0 2 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        // Composite multi-arrow 0xF1 lane glyphs: l1 = single straight (status 1, arrow 1),
        // l2 = slight right (45 deg, unselected GRAY=3 -> stem 33 = 0x21) + right (90 deg, highlighted BLUE=2 -> branch 24 = 0x18),
        // colorMask = 3 | (2 << 2) = 11 (0x0b)
        Iap2NotiMsg lanes = new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup l1 = lanes.new Iap2ObjGroup(), l2 = lanes.new Iap2ObjGroup();
        l1.add(1, U16, i(1)).add(2, U16, i(0));
        l2.add(2, S16, i(90, 45)).add(3, S16, i(90));
        lanes.getItemGroup().add(0, U16, i(1)).add(2, GROUPS, new Iap2NotiMsg.Iap2ObjGroup[] { l1, l2 });
        t.lanes(lanes);
        StringBuilder z = new StringBuilder();
        for (int k = 0; k < 14; k++) z.append("00");
        expect("composite multi-arrow lanes: straight (1) + joined right/slight-right stem (0x21, 0x18) with per-part color mask 0x0b",
                "lanes f10200010b" + z + "0100002118");
        t.lanes(lanes);
        expect("identical lane frame deduplicated: not resent");

        Car.unit = Car.DISTANCE_KM;
        t.routeGuidance(msg(0, U16, i(1), 7, U32, i(800), 11, STR, s("1.5"), 12, ENUM, i(KM)));
        expect("km: turn in the phone's km, trip in m (owner's km, under 1 km), turns ahead in m/km",
                "road [Main St]", "tbt 67 0 0.0 1.5 0 0 1 0 0", "2c 1 5 1 0 0 0 0 0 0", "sub 800.0 0 0 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] 800.0 0} {2 72 0 [Pine St] [] 2.0 1}");
        Car.unit = Car.DISTANCE_MILES;

        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 5, U32, i(250)));
        t.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.1"), 12, ENUM, i(MILES)));
        expect("maneuver distance-only partial update retains its explicit right-turn angle and direction",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 0.4 0 2 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");
        t.maneuver(maneuver(0, RIGHT, 0, 0, 90, "Main St", 250));

        t.routeGuidance(msg(0, U16, i(1), 7, U32, i(100), 6, U64, l(0), 5, U64, l(0)));
        expect("trip under 161 m with miles: feet; no arrival time",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 328.0 0 3 -1 -1 -1 -1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 7, U32, i(0), 6, U64, l(600)));
        Calendar in10 = Calendar.getInstance();
        in10.add(Calendar.SECOND, 600);
        expect("arrival from the time remaining (no distance)",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0",
                "sub 0.0 -1 0 " + in10.get(Calendar.HOUR_OF_DAY) + " " + in10.get(Calendar.MINUTE) + " 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 6, U64, l(0), 5, U64, l(600000000L)));
        expect("arrival as seconds since 2001 (Apple's epoch): 2020-01-06 10:40",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 0.0 -1 0 10 40 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 19, STR, s("Waze"), 5, U64, l(0)));
        check("source change: route guidance restarted", restarted.await(2, TimeUnit.SECONDS));
        expect("source change (Maps to Waze): HUD cleared, then reset state 0 restores compass cleanly",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2",
                "dest 0 []", "road []", "list",
                "tbt 8 0 0.0 0.0 0 0 0 0 -1", "sub 0.0 -3 15 -1 -1 -1 -1 0.0 0 -1",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.maneuver(maneuver(5, ARRIVE, 0, 0, 0, "Home Rd", 100));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(6), 4, STR, s("Home"), 11, STR, s("300"), 12, ENUM, i(FEET), 13, U16, i(5)));
        expect("state 6 with destination, arrival maneuver in 300 ft",
                "dest 0 [Home]", "road [Home Rd]", "tbt 112 0 0.0 300.0 0 0 3 0 0", "hud f201700000" + utf16("Home Rd"),
                "2c 3 0 300 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(2)));
        expect("arrived (state 2): icon 3, no HUD list for it", "road [Home Rd]", "tbt 3 0 0.0 300.0 0 0 3 0 0", "2c 3 0 300 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(0)));
        expect("state 0: restores DisplayType 8 (COMPASS) and clears HUD",
                "dest 0 []", "road []", "list",
                "tbt 8 0 0.0 0.0 0 0 0 0 -1", "sub 0.0 -3 15 -1 -1 -1 -1 0.0 0 -1",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1)));
        t.stop();
        c.calls.clear();
        t.stop();
        expect("route guidance stopped when not showing guidance: HUD cleared only",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.disconnected();
        expect("iAP2 session ended: HUD cleared", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("0.5"), 12, ENUM, i(MILES), 13, U16, i(5), 19, STR, s("Maps")));
        expect("after the session ended: maneuvers gone, a new source isn't a change");
        c.calls.clear();

        t.maneuver(msg(0, U16, i(7), 1, U16, i(0), 3, ENUM, i(LEFT), 4, STR, s("Comp 7 Rd"), 8, ENUM, i(0), 9, ENUM, i(0), 11, U8, i(-60)));
        t.routeGuidance(msg(0, U16, i(7), 1, ENUM, i(1), 11, STR, s("0.3"), 12, ENUM, i(MILES), 13, U16, i(0)));
        expect("a second component keeps its own state", "road [Comp 7 Rd]", "tbt 71 0 0.0 0.3 0 0 2 0 0",
                "hud f201470000" + utf16("Comp 7 Rd"), "2c 2 3 0 0 0 0 0 0 0");

        t.maneuver(msg(1, U16, i(0), 3, ENUM, i(STRAIGHT), 4, STR, s("Any Rd"), 8, ENUM, i(0), 9, ENUM, i(0)));
        t.routeGuidance(msg(1, ENUM, i(1), 11, STR, s("0.4"), 13, U16, i(0)));
        expect("no component ids: every known component gets it",
                "road [Any Rd]", "tbt 65 0 0.0 0.4 0 0 2 0 0", "hud f201410000" + utf16("Any Rd"), "2c 2 4 0 0 0 0 0 0 0",
                "road [Any Rd]", "tbt 65 0 0.0 0.4 0 0 2 0 0", "2c 2 4 0 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(0), 1, ENUM, i(1), 11, STR, s("0.4"), 13, U16, i(0)));
        expect("component 0's empty state=0 does NOT clobber active guidance on components 1 & 7",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        // ---- 2. Icon coverage: sharp/slight, highway exits, forks, RHT & LHT roundabouts, never 64
        check("icons: sharp left 69, slight right 66, RHT roundabout 96, LHT clockwise roundabout 192, U-turn LHT 73",
                CpTurns.icon(1, 0, 0, -120, 1) == 69
                        && CpTurns.icon(2, 0, 0, 60, 1) == 66
                        && CpTurns.icon(2, 0, 1, 0, 1) == 96
                        && CpTurns.icon(31, 1, 1, 0, 1) == 192
                        && CpTurns.icon(4, 1, 0, 0, 1) == 73
                        && CpTurns.icon(47, 0, 0, -140, 1) == 69
                        && CpTurns.icon(99, 0, 0, 0, 1) == 0);
        check("roundabout -45 deg (315 deg slight-left exit) maps to 107 (not 104), LHT +96 -> 203",
                CpTurns.icon(32, 0, 1, true, -45, 1, -1) == 107
                        && CpTurns.icon(32, 1, 1, true, -45, 1, -1) == 203
                        && CpTurns.roundaboutDir(107) == 315
                        && CpTurns.roundaboutDir(203) == 315);
        check("highway exits (8, 22, 23) and keep/forks (13, 14, 52, 53) with or without exit angles",
                CpTurns.icon(8, 0, 0, false, 0, 1, -1) == 81
                        && CpTurns.icon(8, 1, 0, false, 0, 1, -1) == 82
                        && CpTurns.icon(8, 0, 0, true, -25, 1, -1) == 82
                        && CpTurns.icon(22, 0, 0, true, -30, 1, -1) == 82
                        && CpTurns.icon(23, 0, 0, true, 30, 1, -1) == 81
                        && CpTurns.icon(13, 0, 0, true, -20, 1, -1) == 174
                        && CpTurns.icon(14, 0, 0, true, 20, 1, -1) == 173);
        check("aheadIcon never returns 64 (cluster spinner) for any maneuver type",
                noSpinnerInAheadIcons());
        check("lane angles: 0 straight, 90 right, -45 slight left, 180 back, >360 wrapped",
                CpTurns.laneIcon(0) == 1 && CpTurns.laneIcon(90) == 3 && CpTurns.laneIcon(-45) == 8
                        && CpTurns.laneIcon(180) == 5 && CpTurns.laneIcon(270) == 7 && CpTurns.laneIcon(450) == 3);

        indiaAndAngleBucketTurns();
        standardTurns();
        edgeCasesAndSmooth();
        System.exit(fail);
    }

    static boolean noSpinnerInAheadIcons() {
        for (int type = 0; type <= 60; type++) {
            for (int side = -1; side <= 2; side++) {
                for (int junc = 0; junc <= 2; junc++) {
                    int ic = CpTurns.aheadIcon(type, side, junc, 0);
                    if (ic == 64 || ic < 65) return false;
                }
            }
        }
        return true;
    }

    static boolean is(int[] got, int type, int dir, int bar) {
        return got[0] == type && got[1] == dir && got[2] == bar;
    }

    /** Enhanced native cluster & AR HUD format + junction side streets */
    static void standardTurns() {
        c = new Cluster();
        CpTurns t = new CpTurns(new Context(), c, null);
        // Maneuver with 4-way crossroad junction angles: road ahead (0), left (-90), exit right (+90) -> 0x811 (2065)
        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 3, ENUM, i(2), 4, STR, s("Test Road"),
                5, U32, i(900), 8, ENUM, i(0), 9, ENUM, i(0), 10, S16, i(0, -90), 11, S16, i(90)));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 10, U32, i(900), 11, STR, s("900"), 12, ENUM, i(2), 13, U16, i(0)));
        expect("standard turns: 900 m out is the icon (sides 0), bar off",
                "road [Test Road]", "tbt 67 0 0.0 900.0 0 0 0 0 0",
                "hud f201430000" + utf16("Test Road"), "2c 0 0 900 0 0 0 0 0 0");
        t.routeGuidance(msg(0, U16, i(1), 10, U32, i(300), 11, STR, s("300")));
        expect("standard turns: 300 m out is TURN 90 with junction sideStreets 2065 (0x811) and bar 60; HUD keeps icon",
                "road [Test Road]", "tbt 13 2065 90.0 300.0 0 0 0 60 0", "2c 0 0 300 0 0 0 0 0 0");
        t.routeGuidance(msg(0, U16, i(1), 10, U32, i(5), 11, STR, s("5")));
        expect("standard turns: 5 m out the bar is 10 at least",
                "road [Test Road]", "tbt 13 2065 90.0 5.0 0 0 0 10 0", "2c 0 0 5 0 0 0 0 0 0");

        check("sideStreets: 4-way crossroad 0x811, T-junction 0x810, roundabout/mismatch refused",
                CpTurns.sideStreets(2, 0, true, 90, i(0, -90)) == 0x811
                        && CpTurns.sideStreets(2, 0, true, 90, i(-90)) == 0x810
                        && CpTurns.sideStreets(2, 1, true, 90, i(0, -90)) == 0
                        && CpTurns.sideStreets(1, 0, true, 90, i(0)) == 0);

        check("standard: the window is 457 m (bar 90 at its edge), the icon beyond it and with no distance",
                is(CpTurns.standard(2, 0, 0, 0, 67, 457), 13, 90, 90) && is(CpTurns.standard(2, 0, 0, 0, 67, 458), 67, 0, 0)
                        && is(CpTurns.standard(2, 0, 0, 0, 67, -1), 67, 0, 0) && is(CpTurns.standard(1, 0, 0, 0, 70, 0), 13, 270, 10));
        check("standard: straight TURN 0; exit angles in 45 degree steps (-60 315, -120 225, 120 135, 45 45)",
                is(CpTurns.standard(3, 0, 0, 0, 65, 200), 13, 0, 40) && is(CpTurns.standard(1, 0, 0, -60, 71, 200), 13, 315, 40)
                        && is(CpTurns.standard(1, 0, 0, -120, 69, 200), 13, 225, 40) && is(CpTurns.standard(2, 0, 0, 120, 68, 200), 13, 135, 40)
                        && is(CpTurns.standard(2, 0, 0, 45, 66, 200), 13, 45, 40));
        check("standard: slight and sharp turns by type (49 315, 50 45, 47 225, 48 135)",
                is(CpTurns.standard(49, 0, 0, -45, 71, 200), 13, 315, 40) && is(CpTurns.standard(50, 0, 0, 45, 66, 200), 13, 45, 40)
                        && is(CpTurns.standard(47, 0, 0, -140, 69, 200), 13, 225, 40) && is(CpTurns.standard(48, 0, 0, 140, 68, 200), 13, 135, 40));
        check("standard: U-turn 270 (90 for left-hand traffic), forks (13/14/52/53), highway exits (8/22/23)",
                is(CpTurns.standard(4, 0, 0, 0, 72, 200), 31, 270, 40) && is(CpTurns.standard(4, 1, 0, 0, 73, 200), 31, 90, 40)
                        && is(CpTurns.standard(13, 0, 0, -20, 174, 200), 25, 270, 40) && is(CpTurns.standard(14, 0, 0, 20, 173, 200), 25, 90, 40)
                        && is(CpTurns.standard(52, 0, 0, 0, 174, 200), 25, 270, 40) && is(CpTurns.standard(53, 0, 0, 0, 173, 200), 25, 90, 40)
                        && is(CpTurns.standard(8, 0, 0, 30, 81, 200), 17, 90, 40) && is(CpTurns.standard(8, 0, 0, -30, 82, 200), 18, 270, 40)
                        && is(CpTurns.standard(22, 0, 0, 0, 82, 200), 18, 270, 40) && is(CpTurns.standard(23, 0, 0, 0, 81, 200), 17, 90, 40));
        check("standard: roundabout exit icons as 32 (33 left-hand traffic) with the icon's direction",
                is(CpTurns.standard(29, 0, 1, 90, 99, 200), 32, 90, 40) && is(CpTurns.standard(32, 0, 1, -45, 107, 200), 32, 315, 40)
                        && is(CpTurns.standard(1, 1, 1, -90, 201, 200), 33, 270, 40) && is(CpTurns.standard(2, 0, 1, 0, 96, 200), 32, 0, 40));
        check("standard: unsided on-ramps and ferries keep their icon with the bar; sided on-ramps animate as TURN 45/315",
                is(CpTurns.standard(9, 0, 0, 0, 172, 200), 172, 0, 40)
                        && is(CpTurns.standard(9, 0, 0, 30, 173, 200), 13, 45, 40)
                        && is(CpTurns.standard(15, 0, 0, 0, 176, 200), 176, 0, 40));
        check("standard: destinations keep their icon, no bar; ARRIVED within 20 m and in state 2; NO_INFO left alone",
                is(CpTurns.standard(12, 0, 0, 0, 112, 300), 112, 0, 0) && is(CpTurns.standard(25, 0, 0, 0, 113, 100), 113, 0, 0)
                        && is(CpTurns.standard(12, 0, 0, 0, 112, 15), 3, 0, 0) && is(CpTurns.standard(12, 0, 0, 0, 3, -1), 3, 0, 0)
                        && is(CpTurns.standard(11, 0, 0, 0, 1, 200), 1, 0, 0));
        check("turnDir: an angle pointing the other way keeps the plain turn",
                CpTurns.turnDir(-90, true) == 90 && CpTurns.turnDir(90, false) == 270 && CpTurns.turnDir(-90, false) == 270);
        check("roundaboutDir: the clockwise set (+96), and -1 for 103, 106 and turn icons",
                CpTurns.roundaboutDir(195) == 90 && CpTurns.roundaboutDir(103) == -1 && CpTurns.roundaboutDir(106) == -1
                        && CpTurns.roundaboutDir(67) == -1);
    }

    static void edgeCasesAndSmooth() {
        Car.unit = Car.DISTANCE_MILES;
        c = new Cluster();
        Handler h = new Handler();
        CpTurns p = new CpTurns(new Context(), c, null, h);

        // 1. Parked-start fix: RouteGuidanceUpdate arrives BEFORE ManeuverUpdate -> renders immediately once ManeuverUpdate arrives!
        p.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 10, U32, i(100), 12, ENUM, i(4), 13, U16, i(0)));
        expect("parked start: RouteGuidanceUpdate before maneuver details waits quietly");
        p.maneuver(maneuver(0, 2, 0, 0, 90, "Park Ave", 500));
        expect("parked start: late ManeuverUpdate renders immediately using u.distNext in owner's feet (no '0 ft')",
                "road [Park Ave]", "tbt 13 0 90.0 328.0 0 0 3 20 0",
                "hud f201430000" + utf16("Park Ave"), "2c 3 0 328 0 0 0 0 0 0");

        // 2. Advancing to a new maneuver with NO distance yet holds turn frame (road name only, never fake "0 ft")
        p.maneuver(maneuver(1, 1, 0, 0, -90, "Broad St", 800));
        p.routeGuidance(msg(0, U16, i(1), 13, U16, i(1)));
        expect("maneuver change without distance yet: updates road name only, never flashes old 328 ft or fake 0 ft",
                "road [Broad St]");

        // 3. Stale-turn clearing when an unsupported maneuver replaces a shown turn
        p.routeGuidance(msg(0, U16, i(1), 10, U32, i(200), 11, STR, s("0.1"), 12, ENUM, i(1), 13, U16, i(1)));
        c.calls.clear();
        p.maneuver(maneuver(2, 99, 0, 0, 0, "Mystery Way", 200));
        p.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.1"), 13, U16, i(2)));
        expect("unsupported maneuver clears previous turn arrow to NO_INFO (1) instead of leaving stale arrow",
                "road [Mystery Way]", "tbt 1 0 0.0 0.0 -1 0 0 0 -1",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        // 4. 3-arrow composite lane encoding (left -90 + straight 0 + right +90, straight highlighted)
        Iap2NotiMsg l3msg = new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup g3 = l3msg.new Iap2ObjGroup();
        g3.add(2, S16, i(-90, 0, 90)).add(3, S16, i(0));
        int[] enc3 = CpTurns.laneOne(g3);
        // Sorted icons: 1 (0 deg, BLUE=2 -> 47), 3 (90 deg, GRAY=3 -> 53), 7 (-90 deg, GRAY=3 -> 49); mask = 2 | 3<<2 | 3<<4 = 62
        check("3-arrow composite lane: parts {47, 53, 49} and per-part color mask 62",
                enc3[0] == 62 && enc3[1] == 47 && enc3[2] == 53 && enc3[3] == 49);

        // 5. Smooth 250 ms short-unit (UNIT_M) approaching-turn countdown on CarPlay
        CpTurns.testNow = 20000L;
        Handler sh = new Handler();
        CpTurns sm = new CpTurns(new Context(), c, null, sh);
        sm.maneuver(maneuver(0, 2, 0, 0, 90, "Cedar St", 500));
        sm.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 10, U32, i(300), 11, STR, s("300"), 12, ENUM, i(2), 13, U16, i(0)));
        c.calls.clear();
        CpTurns.testNow = 21000L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(280), 11, STR, s("280"), 12, ENUM, i(2)));
        c.calls.clear();
        check("CarPlay smooth 250 ms tick scheduled from observed velocity (20 m/s)", sh.scheduled.containsKey(sm.smooth));
        CpTurns.testNow = 21500L;
        sm.smooth.run();
        expect("CarPlay smooth t=500ms: glides down to 270 m, bar drops to 50",
                "road [Cedar St]", "tbt 13 0 90.0 270.0 0 0 0 50 0", "2c 0 0 270 0 0 0 0 0 0");

        // 6. CarPlay .4 -> .3 -> .2 -> .1 -> ft anti-oscillation + UNIT_FT 10-ft glide + red-light clamp
        sm.maneuver(maneuver(1, 1, 0, 0, -90, "Maple Ave", 800));
        CpTurns.testNow = 30000L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(610), 11, STR, s("0.4"), 12, ENUM, i(1), 13, U16, i(1)));
        c.calls.clear();
        CpTurns.testNow = 31000L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(520), 11, STR, s("0.3"), 12, ENUM, i(1)));
        expect("CarPlay drops cleanly from 0.4 mi to 0.3 mi",
                "road [Maple Ave]", "tbt 70 0 0.0 0.3 0 0 2 0 0", "2c 2 3 0 0 0 0 0 0 0");
        check("CarPlay UNIT_MI suppresses 250 ms sub-tenth interpolation (no .4 <-> .3 flip)",
                !sh.scheduled.containsKey(sm.smooth));

        CpTurns.testNow = 32000L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(570), 11, STR, s("0.4"), 12, ENUM, i(1)));
        expect("CarPlay .4/.3 boundary wobble stays clamped at 0.3 mi",
                "road [Maple Ave]", "tbt 70 0 0.0 0.3 0 0 2 0 0", "2c 2 3 0 0 0 0 0 0 0");

        // nextStr-only wobble (without item 10 distNext): 0.2 mi -> 0.3 mi wobble stays 0.2 mi!
        CpTurns.testNow = 33000L;
        sm.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.2"), 12, ENUM, i(1)));
        expect("CarPlay nextStr-only 0.2 mi enters GUIDE_WINDOW (TURN 270, bar 70)",
                "road [Maple Ave]", "tbt 13 0 270.0 0.2 0 0 2 70 0", "2c 2 2 0 0 0 0 0 0 0");
        CpTurns.testNow = 34000L;
        sm.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.3"), 12, ENUM, i(1)));
        expect("CarPlay nextStr-only 0.3 mi wobble stays clamped at 0.2 mi, bar 70",
                "road [Maple Ave]", "tbt 13 0 270.0 0.2 0 0 2 70 0", "2c 2 2 0 0 0 0 0 0 0");

        // Transition into feet: 400 ft -> 340 ft -> 250 ms glide in 10-ft steps -> red-light clamp
        CpTurns.testNow = 35000L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(122), 11, STR, s("400"), 12, ENUM, i(4)));
        c.calls.clear();
        CpTurns.testNow = 36000L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(102), 11, STR, s("340"), 12, ENUM, i(4)));
        c.calls.clear();
        check("CarPlay UNIT_FT schedules 250 ms smooth countdown", sh.scheduled.containsKey(sm.smooth));
        CpTurns.testNow = 36500L;
        sm.smooth.run();
        expect("CarPlay UNIT_FT glides down in 10-ft steps to 310 ft",
                "road [Maple Ave]", "tbt 13 0 270.0 310.0 0 0 3 20 0", "2c 3 0 310 0 0 0 0 0 0");
        CpTurns.testNow = 37500L;
        sm.routeGuidance(msg(0, U16, i(1), 10, U32, i(105), 11, STR, s("350"), 12, ENUM, i(4)));
        expect("CarPlay red-light GPS upward wobble (350 ft after 310 ft) stays clamped at 310 ft",
                "road [Maple Ave]", "tbt 13 0 270.0 310.0 0 0 3 20 0", "2c 3 0 310 0 0 0 0 0 0");
        check("CarPlay stationary at red light pauses smooth countdown", !sh.scheduled.containsKey(sm.smooth));

        CpTurns.testNow = -1;
    }
}
