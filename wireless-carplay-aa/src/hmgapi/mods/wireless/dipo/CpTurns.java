package hmgapi.mods.wireless.dipo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.text.format.DateFormat;

import com.tcc.iap2.Iap2NotiMsg;

import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * CarPlay turn-by-turn on the cluster and HUD, fed by HMG-API's iAP2 listener (notifications
 * 120 RouteGuidanceUpdate, 121 RouteGuidanceManeuverUpdate, 122 LaneGuidance) and sending
 * through HMG-API's Cluster API without claiming cluster alive/operationStatus:
 *
 *   - Enhanced native cluster scenes (TURN, EXIT_RIGHT/LEFT, FORK_2, U_TURN, ROUNDABOUT)
 *     within GUIDE_WINDOW, with intersection side streets from the iPhone's junction angles
 *     (items 10 & 11) and AR HUD animation (standardTurns, on by default).
 *   - Angle-bucketed turns and roundabouts across all iAP2 ManeuverTypes (ordinary turns at
 *     any angle, slight/sharp turns, highway exits with or without side/angle, keep left/right,
 *     end-of-road, RHT/LHT roundabouts, exit-count fallbacks, and partial-update preservation)
 *     so Apple Maps, Google Maps, Waze, Kakao, Naver and T-Map all render cleanly.
 *   - Owner distance units (USM), zero "0 ft" flashes on partial updates, parked-start late
 *     maneuver rendering, stale-turn clearing, observed-velocity 250 ms distance countdown,
 *     and stock composite multi-arrow 0xF1 lane glyphs.
 *   - Clean compass restoration (DisplayType 8 with live car heading + NaviBridge stock
 *     compass replay) and trap-frame countdown bar broadcasts to com.mnsoft.navi.
 */
final class CpTurns {
    static final int TYPE_ARRIVED = 3, TYPE_COMPASS = 8, TYPE_TURN = 13,
            TYPE_EXIT_RIGHT = 17, TYPE_EXIT_LEFT = 18, TYPE_FORK = 25,
            TYPE_U_TURN = 31, TYPE_ROUNDABOUT_RIGHT = 32, TYPE_ROUNDABOUT_LEFT = 33;
    static final int DEST_AHEAD = 112, DEST_RIGHT = 113, DEST_LEFT = 114;
    static final int GUIDE_WINDOW = 457;
    static final int SMOOTH_MAX_M = GUIDE_WINDOW;
    static final int WRONG_WAY_RESET_M = 60, WRONG_WAY_APPROX_M = 200;
    static final long SMOOTH_MS = 250, SMOOTH_STALE_MS = 1750;
    static final long PHONE_HEARTBEAT_MS = 2000;

    static final String NAVI_GPS_ACTION = "com.hkmc.telematics.gis.action.NAVI_GPS_CHANGED";
    static final String NAVI_HEADING_EXTRA = "com.hkmc.telematics.gis.extra.HEADING";
    static final String ACTION_PHONE_TBT = "hmgapi.mods.wireless.PHONE_TBT";
    static final String NAVI_PKG = "com.mnsoft.navi";
    static final long HEADING_FRESH_MS = 30000L;
    static final float COMPASS_STEP = 22.5f;
    private static final String ROUNDABOUT_PREFIX = "At the roundabout, ";

    // Stock 0xF1 lane per-part colors
    private static final int LANE_GRAY = 3, LANE_WHITE = 1, LANE_BLUE = 2;

    /** Host tests set testNow >= 0 for deterministic timing; -1 uses monotonic clock. */
    static long testNow = -1;

    static long now() {
        return testNow >= 0 ? testNow : System.nanoTime() / 1000000L;
    }

    final Context context;
    final Cluster cluster;
    final Runnable restart;
    final Handler handler;
    long unitCacheMs;
    private int cachedOwnerUnit = -2;
    private long cachedOwnerAt = -100000L;

    final LinkedHashMap<Integer, Update> updates = new LinkedHashMap<Integer, Update>();
    final LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>> maneuvers = new LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>>();
    String source;
    int hudType = -1, hudNext;
    String hudRoad;
    byte[] lastLaneFrame;

    /** Enhanced native cluster & AR HUD scenes + junction side streets (on by default). */
    boolean standardTurns = true;
    boolean sideStreets = true;
    boolean showingGuidance;
    int shownId = -1, shownIndex = -1, shownType = -1;
    int lastRoundaboutIcon = -1;

    String lastTurnLog, lastRouteLog;

    // Smooth approaching-turn countdown state (short units ft/m/yd only)
    private int smCompId = -1, smManeuverIdx = -1, smClusterUnit = -1;
    private int prevRawMeters = -1, smBaseMeters = -1;
    private float smBaseDist = -1f, smFloorDist = 0f, smEstDist = -1f, smSpeed;
    private long prevRawAt = -10000L, smAt;
    final Runnable smooth = new Runnable() {
        @Override
        public void run() {
            smoothTick();
        }
    };

    // Car GPS heading & NaviBridge state
    private BroadcastReceiver headingReceiver;
    private int headingDeg = -1;
    private long headingAt = -100000L;
    private int lastBroadcastBar = -2;
    private long lastBroadcastAt = -100000L;

    CpTurns(Context context, Cluster cluster, Runnable restart) {
        this(context, cluster, restart, null);
    }

    CpTurns(Context context, Cluster cluster, Runnable restart, Handler handler) {
        this.context = context;
        this.cluster = cluster;
        this.restart = restart;
        this.handler = handler;
        watchHeading();
    }

    private int ownerDistanceUnit() {
        if (unitCacheMs <= 0) return Car.distanceUnit(context);
        long t = now();
        if (cachedOwnerUnit != -2 && t - cachedOwnerAt < unitCacheMs) return cachedOwnerUnit;
        cachedOwnerUnit = Car.distanceUnit(context);
        cachedOwnerAt = t;
        return cachedOwnerUnit;
    }

    private void watchHeading() {
        if (headingReceiver != null || context == null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null) return;
                int d = i.getIntExtra(NAVI_HEADING_EXTRA, -1);
                if (d >= 0 && d <= 360) {
                    headingDeg = d;
                    headingAt = now();
                }
            }
        };
        try {
            context.registerReceiver(r, new IntentFilter(NAVI_GPS_ACTION));
            headingReceiver = r;
        } catch (Throwable ignored) {
            headingReceiver = null;
        }
    }

    static float clusterCompassDeg(int heading) {
        if (heading < 0 || heading > 360) return 0f;
        return Math.round((360f - heading) / COMPASS_STEP) * COMPASS_STEP % 360f;
    }

    private void notifyNaviBridge(boolean active, int bar) {
        if (context == null) return;
        long t = now();
        if (active && bar == lastBroadcastBar && t - lastBroadcastAt < PHONE_HEARTBEAT_MS) return;
        lastBroadcastBar = active ? bar : -2;
        lastBroadcastAt = t;
        try {
            Intent i = new Intent(ACTION_PHONE_TBT);
            i.setPackage(NAVI_PKG);
            i.putExtra("source", "cp");
            i.putExtra("active", active);
            i.putExtra("bar", active ? bar : -1);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** RouteGuidanceUpdate (120) */
    void routeGuidance(Iap2NotiMsg msg) {
        if (msg == null) return;
        sourceCheck(msg);
        int[] ids = componentIds(msg);
        if (ids == null) {
            for (Integer id : updates.keySet()) {
                Update u = updates.get(id);
                if (u != null) u.read(id, msg);
                turn(id, u, false);
            }
            return;
        }
        for (int k = 0; k < ids.length; k++) {
            Integer id = ids[k];
            Update u = updates.get(id);
            if (u != null) {
                u.read(id, msg);
            } else {
                u = new Update(id, msg);
                updates.put(id, u);
            }
            turn(id, u, false);
        }
    }

    static int[] componentIds(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return null;
        int[] ids = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) return null;
            if (it.getItemParamType() == 5 && it.getItemId() == 0 && it.getItemObjCount() > 0) ids = it.getItemIntArray();
        }
        return ids;
    }

    /** RouteGuidanceManeuverUpdate (121): stores maneuver and renders immediately if it is current (parked-start fix). */
    void maneuver(Iap2NotiMsg msg) {
        if (msg == null) return;
        int index = -1;
        int[] ids = null;
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g != null) {
            int[] found = null;
            boolean whole = true;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) {
                    whole = false;
                    break;
                }
                int id = it.getItemId();
                if (it.getItemObjCount() <= 0 || (id != 0 && id != 1)) continue;
                int[] v = it.getItemIntArray();
                if (v == null || v.length == 0) continue;
                if (id == 0) found = v;
                else index = v[0];
            }
            if (whole) ids = found;
        }
        if (index < 0) return;
        if (ids == null) {
            for (Integer id : maneuvers.keySet()) {
                LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
                if (m == null) continue;
                Maneuver x = m.get(index);
                if (x != null) x.read(index, id, msg);
                else m.put(index, new Maneuver(index, id, msg));
                refreshIfCurrent(id, index);
            }
            return;
        }
        for (int k = 0; k < ids.length; k++) {
            int id = ids[k];
            LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
            if (m != null) {
                Maneuver x = m.get(index);
                if (x != null) x.read(index, id, msg);
                else m.put(index, new Maneuver(index, id, msg));
            } else {
                m = new LinkedHashMap<Integer, Maneuver>();
                m.put(index, new Maneuver(index, id, msg));
                maneuvers.put(id, m);
            }
            refreshIfCurrent(id, index);
        }
    }

    private void refreshIfCurrent(int id, int maneuverIndex) {
        Update u = updates.get(id);
        if (u != null && u.waitingManeuver && u.state != 0 && u.list != null
                && u.list.length > 0 && u.list[0] == maneuverIndex) {
            turn(id, u, false);
        }
    }

    /** LaneGuidance (122): up to 8 lanes encoded with Gen5W composite multi-arrow glyphs (0xF1). */
    void lanes(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
        if (g == null) return;
        int[] status = new int[8], a = new int[8], b = new int[8], c = new int[8];
        int n = 0;
        Iap2NotiMsg.Iap2ObjGroup[] lanes = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it != null && it.getItemParamType() == 13 && it.getItemId() == 2) lanes = it.getItemGroupArray();
        }
        if (lanes != null) {
            for (int k = 0; k < lanes.length && n < 8; k++) {
                int[] one = laneOne(lanes[k]);
                status[n] = one[0];
                a[n] = one[1];
                b[n] = one[2];
                c[n] = one[3];
                n++;
            }
        }
        byte[] f = new byte[81];
        f[0] = (byte) 0xF1;
        f[1] = (byte) n;
        for (int k = 0; k < n; k++) {
            f[k + 3] = (byte) status[k];
            f[k * 3 + 19] = (byte) a[k];
            f[k * 3 + 20] = (byte) b[k];
            f[k * 3 + 21] = (byte) c[k];
        }
        if (lastLaneFrame != null && Arrays.equals(lastLaneFrame, f)) return;
        lastLaneFrame = f;
        cluster.laneInfo(f);
    }

    /**
     * Encodes one lane into {partColors, part0, part1, part2} using Gen5W's composite
     * multi-arrow stem glyph IDs (9..33 for 2 arrows, 47..54 for 3 arrows) and 2-bit
     * per-part color mask (GRAY=3, WHITE=1, BLUE=2). For a single-arrow lane, preserves
     * standard status 1 (usable) / 2 (highlighted) and arrow ID 1..8.
     */
    static int[] laneOne(Iap2NotiMsg.Iap2ObjGroup g) {
        int[] r = new int[4];
        r[0] = 1;
        if (g == null) return r;
        int rawStatus = -1;
        int[] angles = null, highlight = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) continue;
            int t = it.getItemParamType();
            if (t != 1 && t != 5 && t != 8) continue;
            int[] v = it.getItemIntArray();
            if (v == null || v.length == 0) continue;
            int id = it.getItemId();
            if (id == 1) rawStatus = v[0];
            else if (id == 2) angles = v;
            else if (id == 3) highlight = v;
        }
        boolean[] present = new boolean[9], selected = new boolean[9];
        boolean preferredLane = (rawStatus == 2) || (highlight != null && highlight.length > 0);
        boolean usable = (rawStatus < 0 || rawStatus == 1 || rawStatus == 2);
        if (angles != null) {
            for (int ang : angles) {
                int ic = laneIcon(ang);
                if (ic >= 1 && ic <= 8) present[ic] = true;
            }
        }
        if (usable && highlight != null && highlight.length > 0) {
            for (int ang : highlight) {
                int ic = laneIcon(ang);
                if (ic >= 1 && ic <= 8) {
                    present[ic] = true;
                    selected[ic] = true;
                }
            }
        } else if (usable) {
            for (int ic = 1; ic <= 8; ic++) selected[ic] = present[ic];
        }
        int[] icons = new int[3];
        int count = 0;
        for (int ic = 1; ic <= 8 && count < 3; ic++) {
            if (present[ic]) icons[count++] = ic;
        }
        if (count == 0) return r;
        if (count == 1) {
            r[0] = preferredLane && selected[icons[0]] ? 2 : (usable ? 1 : LANE_GRAY);
            r[1] = icons[0];
            return r;
        }
        if (count == 2 && present[5] && present[7]) {
            icons[0] = 7;
            icons[1] = 5;
        }
        int colorMask = 0, leftParts = 0, precedingParts = 0;
        for (int i = 0; i < count; i++) {
            int ic = icons[i], part = ic;
            if (count == 2 && present[1] && present[7]) part = ic == 1 ? 9 : 12;
            else if (count == 2 && present[1] && present[3]) part = ic == 1 ? 16 : 18;
            else if (count == 2 && present[5] && present[7]) part = ic == 5 ? 10 : 14;
            else if (count > 2) {
                int[] three = { 0, 47, 52, 53, 54, 51, 50, 49, 48 };
                part = three[ic];
            } else {
                switch (ic) {
                    case 1: part = 22; leftParts++; break;
                    case 2: part = precedingParts > 0 ? 23 : 33; break;
                    case 3: part = precedingParts > 0 ? 24 : 32; break;
                    case 4: part = 25; break;
                    case 5: part = 26; leftParts++; break;
                    case 6: part = 27; leftParts++; break;
                    case 7: part = leftParts > 0 ? 28 : 31; leftParts++; break;
                    case 8: part = leftParts > 1 ? 29 : 30; leftParts++; break;
                    default: break;
                }
            }
            int col = selected[ic] ? (preferredLane ? LANE_BLUE : LANE_WHITE) : LANE_GRAY;
            colorMask |= col << (i * 2);
            r[1 + i] = part;
            precedingParts++;
        }
        r[0] = colorMask;
        return r;
    }

    static int laneIcon(int a) {
        a %= 360;
        if (a > 180) a -= 360;
        else if (a < -180) a += 360;
        if (a >= 0) {
            if (a <= 22) return 1;
            if (a <= 67) return 2;
            if (a <= 112) return 3;
            return a <= 157 ? 4 : 5;
        }
        a = -a;
        if (a <= 22) return 1;
        if (a <= 67) return 8;
        if (a <= 112) return 7;
        return a <= 157 ? 6 : 5;
    }

    /** iAP2 session ended */
    void disconnected() {
        reset();
        releaseCluster();
        source = null;
    }

    /** Route guidance stopped (state 0, or native navigation took over) */
    void stop() {
        resetSmooth();
        releaseCluster();
    }

    private void releaseCluster() {
        resetSmooth();
        if (showingGuidance) {
            showingGuidance = false;
            cluster.destinationName(0, "");
            cluster.crossroadName("");
            cluster.turnList(new Cluster.Turn[0]);
            float deg = (headingDeg >= 0 && now() - headingAt <= HEADING_FRESH_MS) ? clusterCompassDeg(headingDeg) : 0f;
            cluster.tbtInfo(TYPE_COMPASS, 0, deg, 0f, 0, 0, 0, 0, -1);
            cluster.tbtSubInfo(0f, -3, 15, -1, -1, -1, -1, deg, 0, -1);
        }
        hudBlank();
        notifyNaviBridge(false, -1);
    }

    /** Forget every component's state and maneuvers */
    void reset() {
        resetSmooth();
        for (LinkedHashMap<Integer, Maneuver> m : maneuvers.values()) {
            if (m != null) m.clear();
        }
        for (Update u : updates.values()) {
            if (u != null) u.clear();
        }
        shownId = -1;
        shownIndex = -1;
        shownType = -1;
        lastRoundaboutIcon = -1;
    }

    /** SourceName (item 19) check: clears previous app's state and triggers serialized restart */
    void sourceCheck(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null || it.getItemId() != 19 || it.getItemParamType() != 10 || it.getItemObjCount() <= 0) continue;
            String[] v = it.getItemStringArray();
            String s = v == null || v.length == 0 ? null : v[0];
            if (s == null || s.length() == 0) continue;
            String was = source;
            source = s;
            if (was == null) {
                Dipo.log("CarPlay: route guidance source " + s);
                return;
            }
            if (was.equals(s)) return;
            Dipo.log("CarPlay: route guidance source changed from " + was + " to " + s + ", clearing turn-by-turn");
            reset();
            hudBlank();
            if (restart != null) restart.run();
            return;
        }
    }

    void turn(Integer id, Update u) {
        turn(id, u, false);
    }

    private boolean anyGuiding() {
        for (Update other : updates.values()) {
            if (other != null && other.state != 0) return true;
        }
        return false;
    }

    /** One component's update to the cluster */
    void turn(Integer id, Update u, boolean fromSmooth) {
        if (id == null || id < 0 || u == null) return;
        if (u.state == 0) {
            if (anyGuiding()) hudBlank();
            else stop();
            return;
        }
        int[] list = u.list == null ? null : u.list.clone();
        if (u.state == 6) cluster.destinationName(0, u.destination);
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        Maneuver x = (m == null || list == null || list.length == 0) ? null : m.get(list[0]);
        if (!fromSmooth) logRoute(id, u, x != null);
        if (x == null) {
            u.waitingManeuver = (list != null && list.length > 0);
            return;
        }
        u.waitingManeuver = false;

        int icon = icon(x.type, x.side, x.junction, x.exitSent, x.exit, u.state, lastRoundaboutIcon);
        if (roundaboutDir(icon) >= 0) lastRoundaboutIcon = icon;
        else if (x.junction == 0 && x.type != 6 && x.type != 7) lastRoundaboutIcon = -1;

        String roadText = cleanRoadText(x.afterRoad);
        if (x.type == 11 && icon == 1) hudBlank();
        cluster.crossroadName(roadText);

        boolean changedManeuver = (shownId != id || shownIndex != list[0] || shownType != x.type);
        if (u.monoManeuverIdx != list[0] || u.monoManeuverType != x.type) {
            u.resetMonotonic(list[0], x.type);
            if (smCompId == id) resetSmooth();
        }
        if (icon == 0) {
            logTurn(x, 0);
            if (changedManeuver && shownType >= 0) {
                shownId = id;
                shownIndex = list[0];
                shownType = -1;
                cluster.tbtInfo(1, 0, 0f, 0f, -1, 0, 0, 0, -1);
                hudBlank();
            }
            return;
        }

        // Resolve live next-turn distance from RouteGuidanceUpdate (item 11 nextStr and/or
        // item 10 distNext). Never fall back to x.distance (item 5 DistanceBetweenManeuvers),
        // which is the static leg length rather than remaining distance to the turn, and never
        // invent a fake "0 ft" when neither distance field has arrived yet.
        boolean hasFormatted = u.nextStr != null && !u.nextStr.equals("");
        float parsedStr = -1f;
        if (hasFormatted) {
            try {
                float p = Float.parseFloat(u.nextStr);
                if (!Float.isNaN(p) && !Float.isInfinite(p) && p >= 0f) parsedStr = p;
            } catch (NumberFormatException ignored) {
            }
        }
        if (!fromSmooth && !u.hasDistNext && parsedStr < 0f && u.state != 2 && icon != 1) {
            // Distance hasn't arrived yet: road name is up, hold turn frame until distance arrives
            sendTrip(u);
            sendAhead(id, u);
            return;
        }

        float dist;
        int unit;
        int owner = ownerDistanceUnit();
        if (fromSmooth) {
            dist = smEstDist;
            unit = smClusterUnit;
        } else if (parsedStr >= 0f) {
            dist = parsedStr;
            unit = iap2UnitToCluster(u.nextUnits);
        } else if (u.hasDistNext) {
            float[] du = metersToCluster(u.distNext, owner, u.nextUnits);
            dist = du[0];
            unit = (int) du[1];
        } else {
            dist = 0f;
            unit = iap2UnitToCluster(u.nextUnits);
        }
        if (dist >= 0f && (unit == Cluster.UNIT_MI || unit == Cluster.UNIT_KM)) {
            dist = Math.round(dist * 10f) / 10f;
        }

        int rawMeters = (fromSmooth || u.hasDistNext) ? u.distNext : approxMeters(parsedStr, u.nextUnits);
        int wrongWayThreshold = (fromSmooth || u.hasDistNext) ? WRONG_WAY_RESET_M : WRONG_WAY_APPROX_M;
        if (icon != 1 && u.state != 2) {
            float[] clamped = u.applyMonotonic(dist, unit, rawMeters, wrongWayThreshold);
            dist = clamped[0];
            unit = (int) clamped[1];
        }

        shownId = id;
        shownIndex = list[0];
        shownType = x.type;
        showingGuidance = true;

        int type = icon, dir = 0, bar = 0, sides = 0, trapBar = 0;
        if (standardTurns) {
            int metersForStd = u.minRawMeters >= 0 ? u.minRawMeters : rawMeters;
            int[] s = standard(x.type, x.side, x.junction, x.exitSent, x.exit, icon, metersForStd);
            type = s[0];
            dir = s[1];
            bar = s[2];
            if (sideStreets && type == TYPE_TURN) {
                sides = sideStreets(x.type, x.junction, x.exitSent, x.exit, x.angles);
            }
            if (type < 64 && type != 1 && type != TYPE_ARRIVED) trapBar = bar;
        }
        if (!fromSmooth) logTurn(x, icon);

        cluster.tbtInfo(type, sides, dir, dist, 0, 0, unit, bar, 0);
        notifyNaviBridge(true, trapBar);
        sendHud(icon, id, u, roadText);
        sendHudDist(dist, unit);
        if (!fromSmooth) {
            sendTrip(u);
            sendAhead(id, u);
            if (standardTurns && handler != null && u.hasDistNext) {
                smoothFrom(id, list[0], u.distNext, dist, unit, u.minRawMeters);
            }
        }
    }

    static float unitsPerMeter(int clusterUnit) {
        if (clusterUnit == Cluster.UNIT_FT) return 3.28084f;
        if (clusterUnit == Cluster.UNIT_YD) return 1.09361f;
        return 1.0f;
    }

    static float quantizeShortSmooth(float d, int clusterUnit) {
        if (d <= 0f) return 0f;
        if (clusterUnit == Cluster.UNIT_FT) {
            return d < 20f ? Math.round(d) : Math.round(d / 10f) * 10f;
        }
        return d < 10f ? Math.round(d) : Math.round(d / 5f) * 5f;
    }

    private void resetSmooth() {
        if (handler != null) handler.removeCallbacks(smooth);
        smCompId = -1;
        smManeuverIdx = -1;
        smClusterUnit = -1;
        prevRawMeters = -1;
        prevRawAt = -10000L;
        smBaseMeters = -1;
        smBaseDist = -1f;
        smFloorDist = 0f;
        smEstDist = -1f;
        smSpeed = 0f;
    }

    private void smoothFrom(int compId, int maneuverIdx, int m, float shownDist, int clusterUnit, int clampedMeters) {
        if (handler == null) return;
        handler.removeCallbacks(smooth);
        long t = now();
        if (compId != smCompId || maneuverIdx != smManeuverIdx) {
            smCompId = compId;
            smManeuverIdx = maneuverIdx;
            prevRawMeters = -1;
        }
        float v = 0f;
        if (prevRawMeters >= 0 && t > prevRawAt && t - prevRawAt <= 3500L) {
            int dm = prevRawMeters - m;
            v = dm <= 0 ? 0f : Math.min(55f, dm * 1000f / (t - prevRawAt));
        }
        prevRawMeters = m;
        prevRawAt = t;

        // Only run 250 ms interpolation in short units (ft/m/yd) inside GUIDE_WINDOW.
        // In miles/km (0.4, 0.3, 0.2, 0.1), the cluster only displays tenths and sub-tenth
        // interpolation fights the phone's rounded tenths due to cluster truncation.
        boolean shortUnit = (clusterUnit == Cluster.UNIT_FT || clusterUnit == Cluster.UNIT_M || clusterUnit == Cluster.UNIT_YD);
        if (!shortUnit || m <= 0 || m > SMOOTH_MAX_M || v < 1.0f || shownDist <= 0f) {
            smBaseMeters = -1;
            return;
        }
        float scale = unitsPerMeter(clusterUnit);
        smBaseMeters = clampedMeters >= 0 ? clampedMeters : m;
        smBaseDist = shownDist;
        smFloorDist = Math.max(0f, (m - v * 1.25f) * scale);
        smClusterUnit = clusterUnit;
        smSpeed = v;
        smAt = t;
        handler.postDelayed(smooth, SMOOTH_MS);
    }

    void smoothTick() {
        if (handler == null || smBaseMeters < 0 || !showingGuidance) return;
        long dt = now() - smAt;
        if (dt > SMOOTH_STALE_MS) return;
        Update u = updates.get(smCompId);
        if (u == null || u.state == 0 || u.list == null || u.list.length == 0 || u.list[0] != smManeuverIdx) return;
        float scale = unitsPerMeter(smClusterUnit);
        float rawEstDist = Math.max(smFloorDist, smBaseDist - (smSpeed * scale) * dt / 1000f);
        float estDist = quantizeShortSmooth(rawEstDist, smClusterUnit);
        int estMeters = Math.max(0, Math.round(smBaseMeters - smSpeed * dt / 1000f));
        if (u.minRawMeters >= 0 && estMeters > u.minRawMeters) estMeters = u.minRawMeters;
        if (u.minShownDist >= 0f && u.minShownUnit == smClusterUnit && estDist > u.minShownDist) estDist = u.minShownDist;

        int prevBar = u.minRawMeters >= 0 ? Math.max(1, Math.min(9, u.minRawMeters * 10 / GUIDE_WINDOW)) : -1;
        int curBar = Math.max(1, Math.min(9, estMeters * 10 / GUIDE_WINDOW));
        boolean barMoved = u.minRawMeters >= 0
                && (prevBar != curBar || (u.minRawMeters >= 20 && estMeters < 20));
        boolean distMoved = u.minShownDist < 0f || estDist < u.minShownDist;
        if (distMoved || barMoved) {
            int saved = u.distNext;
            u.distNext = estMeters;
            smEstDist = estDist;
            try {
                turn(smCompId, u, true);
            } finally {
                u.distNext = saved;
            }
        }
        if (estMeters > 0 && estDist > 0f) handler.postDelayed(smooth, SMOOTH_MS);
    }

    static int iap2UnitToCluster(int nextUnits) {
        switch (nextUnits) {
            case 0: return Cluster.UNIT_KM;
            case 1: return Cluster.UNIT_MI;
            case 3: return Cluster.UNIT_YD;
            case 4: return Cluster.UNIT_FT;
            default: return Cluster.UNIT_M;
        }
    }

    static int approxMeters(float d, int iap2Units) {
        if (d < 0f) return -1;
        switch (iap2Units) {
            case 0: return Math.round(d * 1000f);
            case 1: return Math.round(d * 1609.344f);
            case 3: return Math.round(d * 0.9144f);
            case 4: return Math.round(d * 0.3048f);
            default: return Math.round(d);
        }
    }

    static float[] metersToCluster(int m, int owner, int fallbackIap2Unit) {
        float d;
        int unit;
        if (owner == Car.DISTANCE_KM) {
            if (m < 1000) {
                d = m;
                unit = Cluster.UNIT_M;
            } else {
                d = m / 1000f;
                unit = Cluster.UNIT_KM;
            }
        } else if (owner == Car.DISTANCE_MILES) {
            if (m < 161) {
                d = Math.round(m * 3.28084f);
                unit = Cluster.UNIT_FT;
            } else {
                d = m / 1609f;
                unit = Cluster.UNIT_MI;
            }
        } else {
            int cu = iap2UnitToCluster(fallbackIap2Unit);
            switch (cu) {
                case Cluster.UNIT_KM: d = m / 1000f; unit = Cluster.UNIT_KM; break;
                case Cluster.UNIT_MI: d = m / 1609f; unit = Cluster.UNIT_MI; break;
                case Cluster.UNIT_FT: d = Math.round(m * 3.28084f); unit = Cluster.UNIT_FT; break;
                case Cluster.UNIT_YD: d = Math.round(m * 1.09361f); unit = Cluster.UNIT_YD; break;
                default:
                    if (m >= 1000) { d = m / 1000f; unit = Cluster.UNIT_KM; }
                    else { d = m; unit = Cluster.UNIT_M; }
                    break;
            }
        }
        return new float[] { d, unit };
    }

    static String cleanRoadText(String s) {
        if (s == null || s.length() <= ROUNDABOUT_PREFIX.length()) return s;
        if (!s.regionMatches(true, 0, ROUNDABOUT_PREFIX, 0, ROUNDABOUT_PREFIX.length())) return s;
        String rest = s.substring(ROUNDABOUT_PREFIX.length()).trim();
        return rest.isEmpty() ? s : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    /** Backwards-compatible entry point (assumes exitSent when exit != 0). */
    static int icon(int type, int side, int junction, int exit, int state) {
        return icon(type, side, junction, exit != 0, exit, state, -1);
    }

    /**
     * Maps any iAP2 ManeuverType, driving side (0 RHT, 1 LHT), junction type (0 intersection,
     * 1 roundabout), and optional exit angle (-180..180, right positive) to its cluster icon.
     */
    static int icon(int type, int side, int junction, boolean exitSent, int exit, int state, int prevRoundabout) {
        if (side != 0 && side != 1) return 0;
        boolean lht = (side == 1);
        switch (type) {
            case 20:   // left at end of road
            case 1:    // left turn
                if (junction == 1) return exitSent ? roundaboutIcon(exit, lht) : roundaboutFallback(27, lht);
                if (junction != 0) return 0;
                if (!exitSent || exit == 0) return 70;
                if (exit > 0 || exit < -180) return 0;
                if (exit >= -67) return 71;   // slight left
                if (exit >= -112) return 70;  // normal left
                return 69;                    // sharp left

            case 21:   // right at end of road
            case 2:    // right turn
                if (junction == 1) return exitSent || exit == 0 ? roundaboutIcon(exit, lht) : roundaboutFallback(27, lht);
                if (junction != 0) return 0;
                if (!exitSent || exit == 0) return 67;
                if (exit < 0 || exit > 180) return 0;
                if (exit <= 67) return 66;    // slight right
                if (exit <= 112) return 67;   // normal right
                return 68;                    // sharp right

            case 3:    // straight ahead
                return junction == 0 && (!exitSent || Math.abs(exit) <= 22) ? 65 : 0;

            case 26:   // U-turn when possible
            case 4:    // U-turn
                return junction == 0 ? (lht ? 73 : 72) : 0;

            case 5:    // FollowRoad / OnRamp
            case 9:    // MergeOntoHighway
                if (junction != 0) return 0;
                if (exitSent && exit > 0) return 173;
                if (exitSent && exit < 0) return 174;
                return 172;

            case 6:    // EnterRoundabout
            case 7:    // ExitRoundabout
                if (exitSent && exit != 0) return roundaboutIcon(exit, lht);
                if (type == 7 && prevRoundabout >= 0) return prevRoundabout;
                return 65;

            case 8:    // OffRamp (side inferred from exit angle or traffic kerb side)
                if (junction != 0) return 0;
                return exitSide(side, exitSent ? exit : 0) > 0 ? 81 : 82;

            case 11:   // StartRoute: NO_INFO
                return junction == 0 && (!exitSent || exit == 0) ? 1 : 0;

            case 12:   // Arrive
                if (state == 2) return TYPE_ARRIVED;
                return junction == 0 ? DEST_AHEAD : 0;
            case 24:   // ArriveLeft
                if (state == 2) return TYPE_ARRIVED;
                return junction == 0 ? DEST_LEFT : 0;
            case 25:   // ArriveRight
                if (state == 2) return TYPE_ARRIVED;
                return junction == 0 ? DEST_RIGHT : 0;

            case 13:   // KeepLeft
            case 52:   // ChangeHighwayLeft
                return junction == 0 ? 174 : 0;
            case 14:   // KeepRight
            case 53:   // ChangeHighwayRight
                return junction == 0 ? 173 : 0;

            case 15:   // Ferry
            case 16:   // RailFerry
                return junction == 0 ? 176 : 0;

            case 19:   // RoundaboutUTurn
                return junction == 1 ? roundaboutIcon(exitSent && exit != 0 ? exit : 180, lht) : 0;

            case 22:   // HighwayOffRampLeft (with or without slight exit angle, RHT or LHT)
                return junction == 0 ? 82 : 0;
            case 23:   // HighwayOffRampRight
                return junction == 0 ? 81 : 0;

            case 27: case 28: case 29: case 30: case 31: case 32: case 33:
            case 34: case 35: case 36: case 37: case 38: case 39:
                if (junction != 1) return 0;
                if (exitSent || (type == 31 && exit == 0)) return roundaboutIcon(exit, lht);
                return roundaboutFallback(type, lht);

            case 47:   // SharpLeftTurn
                if (junction != 0) return 0;
                return (exitSent && exit < 0 && exit >= -67) ? 71 : 69;
            case 48:   // SharpRightTurn
                if (junction != 0) return 0;
                return (exitSent && exit > 0 && exit <= 67) ? 66 : 68;
            case 49:   // SlightLeftTurn
                return junction == 0 ? 71 : 0;
            case 50:   // SlightRightTurn
                return junction == 0 ? 66 : 0;

            default:
                return 0;
        }
    }

    /** Side of an off-ramp (+1 right, -1 left) from exit angle if non-zero, else kerb side. */
    static int exitSide(int drivingSide, int exit) {
        if (exit > 0) return 1;
        if (exit < 0) return -1;
        return drivingSide == 1 ? -1 : 1;
    }

    /** Roundabout icon in 45-degree steps: 96..107 for RHT (CCW), 192..203 for LHT (CW). Never 103/106. */
    static int roundaboutIcon(int exit, boolean lht) {
        int a = ((exit % 360) + 360) % 360;
        int[] rht = { 96, 97, 99, 100, 102, 104, 105, 107 };
        int base = rht[(a + 22) / 45 % 8];
        return lht ? base + 96 : base;
    }

    private static int roundaboutFallback(int maneuverType, boolean lht) {
        int exitNum = maneuverType - 27 + 1;   // 27 = exit 1, 28 = exit 2, ...
        int approxDeg;
        if (exitNum <= 1) approxDeg = lht ? -90 : 90;
        else if (exitNum == 2) approxDeg = 0;
        else if (exitNum == 3) approxDeg = lht ? 90 : -90;
        else approxDeg = 180;
        return roundaboutIcon(approxDeg, lht);
    }

    void logRoute(Integer id, Update u, boolean hasManeuver) {
        int cur = (u.list != null && u.list.length > 0) ? u.list[0] : -1;
        boolean hasDist = u.hasDistNext || (u.nextStr != null && !u.nextStr.equals(""));
        String s = "CarPlay route: component " + id + " state " + u.state + ", maneuver " + cur
                + (hasManeuver ? "" : " (no details yet)") + " of " + u.count
                + ", distance " + (hasDist ? "sent" : "none");
        if (s.equals(lastRouteLog)) return;
        lastRouteLog = s;
        Dipo.log(s);
    }

    void logTurn(Maneuver x, int icon) {
        String s = "CarPlay turn: type " + x.type + " side " + x.side + " junction " + x.junction
                + " exit " + (x.exitSent ? String.valueOf(x.exit) : "none")
                + " angles " + (x.angles == null ? "none" : Arrays.toString(x.angles))
                + " -> icon " + icon;
        if (standardTurns && icon > 0) {
            int[] n = standard(x.type, x.side, x.junction, x.exitSent, x.exit, icon, GUIDE_WINDOW);
            s += ", near: type " + n[0] + " dir " + n[1];
            if (n[0] == TYPE_TURN) {
                s += " sides 0x" + Integer.toHexString(sideStreets(x.type, x.junction, x.exitSent, x.exit, x.angles))
                        + (sideStreets ? "" : " (off)");
            }
        }
        if (s.equals(lastTurnLog)) return;
        lastTurnLog = s;
        Dipo.log(s);
    }

    static int[] standard(int type, int side, int junction, int exit, int icon, int meters) {
        return standard(type, side, junction, exit != 0, exit, icon, meters);
    }

    static int[] standard(int type, int side, int junction, boolean exitSent, int exit, int icon, int meters) {
        if (icon < 64) return new int[] { icon, 0, 0 };
        if (icon == DEST_AHEAD || icon == DEST_RIGHT || icon == DEST_LEFT) {
            if (meters >= 0 && meters < 20) return new int[] { TYPE_ARRIVED, 0, 0 };
            return new int[] { icon, 0, 0 };
        }
        if (meters < 0 || meters > GUIDE_WINDOW) return new int[] { icon, 0, 0 };
        int bar = Math.max(1, Math.min(9, meters * 10 / GUIDE_WINDOW)) * 10;
        int r = roundaboutDir(icon);
        if (r >= 0) {
            boolean cw = (side == 1) || (icon >= 192 && icon <= 203);
            return new int[] { cw ? TYPE_ROUNDABOUT_LEFT : TYPE_ROUNDABOUT_RIGHT, r, bar };
        }
        int effExit = exitSent ? exit : 0;
        switch (type) {
            case 3:
            case 6:
            case 7:
                return new int[] { TYPE_TURN, 0, bar };
            case 1:
            case 20:
                return new int[] { TYPE_TURN, turnDir(effExit, false), bar };
            case 2:
            case 21:
                return new int[] { TYPE_TURN, turnDir(effExit, true), bar };
            case 49:
                return new int[] { TYPE_TURN, 315, bar };
            case 50:
                return new int[] { TYPE_TURN, 45, bar };
            case 47:
                return new int[] { TYPE_TURN, (exitSent && exit < 0 && exit >= -67) ? 315 : 225, bar };
            case 48:
                return new int[] { TYPE_TURN, (exitSent && exit > 0 && exit <= 67) ? 45 : 135, bar };
            case 4:
            case 26:
                return new int[] { TYPE_U_TURN, side == 1 ? 90 : 270, bar };
            case 13:
            case 52:
                return new int[] { TYPE_FORK, 270, bar };
            case 14:
            case 53:
                return new int[] { TYPE_FORK, 90, bar };
            case 9:
                if (icon == 173 || icon == 174) {
                    return new int[] { TYPE_TURN, icon == 173 ? 45 : 315, bar };
                }
                return new int[] { icon, 0, bar };
            case 8:
                return icon == 82 ? new int[] { TYPE_EXIT_LEFT, 270, bar } : new int[] { TYPE_EXIT_RIGHT, 90, bar };
            case 22:
                return new int[] { TYPE_EXIT_LEFT, 270, bar };
            case 23:
                return new int[] { TYPE_EXIT_RIGHT, 90, bar };
            default:
                return new int[] { icon, 0, bar };
        }
    }

    /**
     * Builds the 16-bit sideStreet mask (one bit per 22.5 degrees) around a TURN arrow from
     * the iPhone's junction road angles (item 10) and exit angle (item 11).
     */
    static int sideStreets(int type, int junction, boolean exitSent, int exit, int[] angles) {
        if (junction != 0 || !exitSent || angles == null || angles.length == 0 || exit < -180 || exit > 180) return 0;
        boolean left = type == 1 || type == 20 || type == 47 || type == 49;
        boolean right = type == 2 || type == 21 || type == 48 || type == 50;
        if (left ? exit >= 0 : right ? exit <= 0 : type != 3 || Math.abs(exit) > 22) return 0;
        int roads = roadBit(exit);
        for (int a : angles) {
            if (a < -180 || a > 180) return 0;
            roads |= roadBit(a);
        }
        return roads;
    }

    static int roadBit(int angle) {
        int cw = ((angle % 360) + 360) % 360;
        if (cw < 12 || cw > 348) return 1;
        int lo = 12, bit = 1;
        for (int k = 0; k < 15; k++) {
            int hi = lo + (k % 2 == 0 ? 22 : 23);
            if (k != 7) {
                if (cw >= lo && cw < hi) return 1 << bit;
                bit++;
            }
            lo = hi;
        }
        return 0;
    }

    static int turnDir(int exit, boolean right) {
        int plain = right ? 90 : 270;
        if (exit == 0) return plain;
        int a = ((exit % 360) + 360) % 360;
        int d = ((a + 22) / 45) * 45 % 360;
        if (right) return d >= 45 && d <= 135 ? d : plain;
        return d >= 225 && d <= 315 ? d : plain;
    }

    static int roundaboutDir(int icon) {
        int i = icon >= 192 && icon <= 203 ? icon - 96 : icon;
        switch (i) {
            case 96: return 0;
            case 97: return 45;
            case 99: return 90;
            case 100: return 135;
            case 102: return 180;
            case 104: return 225;
            case 105: return 270;
            case 107: return 315;
            default: return -1;
        }
    }

    /** Upcoming turn icon for HUD list and cluster turn list. Never 64 (the cluster's spinner). */
    static int aheadIcon(int type, int side, int junction, int exit) {
        return aheadIcon(type, side, junction, exit != 0, exit);
    }

    static int aheadIcon(int type, int side, int junction, boolean exitSent, int exit) {
        int safeSide = (side == 0 || side == 1) ? side : 0;
        int i = icon(type, safeSide, junction, exitSent, exit, 1, -1);
        return i >= 65 ? i : 65;
    }

    void sendHud(int type, Integer id, Update u, String cleanedRoad) {
        if (type < 64) return;
        int next = 0;
        String road = cleanedRoad == null ? "" : cleanedRoad;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        int[] list = u == null ? null : u.list;
        if (m != null && list != null && list.length > 1) {
            Maneuver y = m.get(list[1]);
            if (y != null && y.distance > 0) next = aheadIcon(y.type, y.side, y.junction, y.exitSent, y.exit);
        }
        if (hudType == type && hudNext == next && hudRoad != null && hudRoad.equals(road)) return;
        byte[] r;
        try {
            r = road.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(r.length, 78);
        byte[] f = new byte[n + 5];
        f[0] = (byte) 0xF2;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(r, 0, f, 5, n);
        Dipo.log("CarPlay: hud list type " + type + " next " + next);
        cluster.turnListNew(f);
        hudType = type;
        hudNext = next;
        hudRoad = road;
    }

    void sendHudDist(float f, int unit) {
        if (f < 0f || unit < 0 || unit > 4) return;
        int whole = (int) f;
        if (whole > 4095) whole = 4095;
        int tenths = 0;
        if (unit == Cluster.UNIT_KM || unit == Cluster.UNIT_MI) {
            tenths = (int) ((f - whole) * 10f + 0.5f);
            if (tenths < 0) tenths = 0;
            if (tenths > 9) tenths = 9;
        }
        cluster.tbtSubInfo2C(unit, tenths, whole, 0, 0, 0, 0, 0, 0);
    }

    void hudBlank() {
        lastLaneFrame = null;
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] lanes = new byte[81];
        lanes[0] = (byte) 0xF1;
        cluster.laneInfo(lanes);
        hudType = -1;
        hudNext = 0;
        hudRoad = null;
        byte[] empty = { (byte) 0xF2 };
        cluster.turnListNew(empty);
        cluster.turnListNew(empty);
    }

    void sendTrip(Update u) {
        int d = u.distLeft, units = u.distLeftUnits;
        int whole = 0, tenths = 0, unit = Cluster.UNIT_M;
        if (d > 0) {
            whole = d;
            int owner = ownerDistanceUnit();
            if (owner == Car.DISTANCE_KM) units = d < 1000 ? 2 : 0;
            else if (owner == Car.DISTANCE_MILES) units = d < 161 ? 4 : 1;
            switch (units) {
                case 0:
                    whole = d / 1000;
                    tenths = (d % 1000) / 100;
                    unit = Cluster.UNIT_KM;
                    break;
                case 1: {
                    int t = (d * 10) / 1609;
                    tenths = t % 10;
                    whole = t / 10;
                    unit = Cluster.UNIT_MI;
                    break;
                }
                case 2:
                    break;
                case 3:
                    whole = (d * 1094) / 1000;
                    unit = Cluster.UNIT_YD;
                    break;
                case 4:
                    whole = (d * 3281) / 1000;
                    unit = Cluster.UNIT_FT;
                    break;
                default:
                    if (d >= 1000) {
                        whole = d / 1000;
                        tenths = (d % 1000) / 100;
                        unit = Cluster.UNIT_KM;
                    }
                    break;
            }
        }
        int hour = -1, minute = -1, timeType = -1, timeFormat;
        Calendar cal = null;
        long eta = u.eta;
        if (eta < 500000000L) {
            long left = u.timeLeft;
            if (left > 0 && left <= 604800L) {
                cal = Calendar.getInstance();
                cal.add(Calendar.SECOND, (int) left);
            }
        } else {
            if (eta < 1500000000L) eta += 978307200L;   // Apple's 2001 epoch
            cal = Calendar.getInstance();
            cal.setTimeInMillis(eta * 1000L);
        }
        if (cal != null) {
            hour = cal.get(Calendar.HOUR_OF_DAY);
            minute = cal.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        } else {
            timeFormat = -1;
        }
        if (d <= 0 && timeType < 0) return;
        cluster.tbtSubInfo(whole + tenths / 10f, d > 0 ? 0 : -1, unit, hour, minute, timeType, timeType < 0 ? -1 : timeFormat, 0f, -1, 0);
    }

    void sendAhead(Integer id, Update u) {
        int[] list = u == null ? null : u.list;
        if (id == null || list == null || list.length <= 1) return;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        if (m == null) return;
        int count = 0;
        for (int k = 1; k < list.length && count < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x != null && x.distance > 0) count++;
        }
        if (count == 0) return;
        int owner = ownerDistanceUnit();
        Cluster.Turn[] turns = new Cluster.Turn[count];
        int n = 0;
        for (int k = 1; k < list.length && n < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x == null || x.distance <= 0 || n >= count) continue;
            Cluster.Turn t = new Cluster.Turn();
            int ic = aheadIcon(x.type, x.side, x.junction, x.exitSent, x.exit);
            t.type = ic;
            int rbDir = roundaboutDir(ic);
            t.direction = rbDir >= 0 ? rbDir : 0;
            t.traffic = 0;
            t.no = n + 1;
            t.name = x.afterRoad == null ? "" : cleanRoadText(x.afterRoad);
            t.exitNumber = "";
            float[] du = metersToCluster(x.distance, owner, x.distUnits);
            float f = du[0];
            if (f < 0f) f = 0f;
            if (f > 65535f) f = 65535f;
            t.distance = f;
            t.unit = (int) du[1];
            turns[n++] = t;
        }
        if (n > 0) cluster.turnList(turns);
    }

    /** One display component's route guidance state */
    static final class Update {
        int state, maneuverState, distLeft, distLeftUnits, distNext, nextUnits, count, laneIndex, laneCount;
        boolean hasDistNext, hasNextUnits, waitingManeuver;
        String road, destination, distLeftStr, nextStr, sourceName;
        long eta, timeLeft;
        int[] list;
        boolean visible, lanesShown, supports;

        // Per-maneuver monotonic distance floor
        int monoManeuverIdx = -1, monoManeuverType = -1;
        int minRawMeters = -1, minShownRank = -1, minShownUnit = -1;
        float minShownDist = -1f;

        Update(int id, Iap2NotiMsg msg) {
            if (msg == null || id <= 0) return;
            clear();
            read(id, msg);
        }

        void resetMonotonic(int idx, int type) {
            monoManeuverIdx = idx;
            monoManeuverType = type;
            minRawMeters = -1;
            minShownRank = -1;
            minShownUnit = -1;
            minShownDist = -1f;
        }

        float[] applyMonotonic(float dist, int unit, int rawM, int wrongWayThreshold) {
            if (dist < 0f) return new float[] { dist, unit };
            int rank = (unit == Cluster.UNIT_MI || unit == Cluster.UNIT_KM) ? 0 : (unit >= 0 && unit <= 4 ? 1 : -1);
            boolean metric = (unit == Cluster.UNIT_M || unit == Cluster.UNIT_KM);
            boolean prevMetric = (minShownUnit == Cluster.UNIT_M || minShownUnit == Cluster.UNIT_KM);
            if (minShownUnit >= 0 && metric != prevMetric) {
                // Distance unit system switched (metric <-> imperial): reset floor
                minRawMeters = -1;
                minShownRank = -1;
                minShownDist = -1f;
            }
            if (rawM >= 0) {
                if (minRawMeters >= 0 && rawM - minRawMeters > wrongWayThreshold) {
                    minRawMeters = rawM;
                    minShownRank = -1;
                    minShownDist = -1f;
                } else if (minRawMeters < 0 || rawM < minRawMeters) {
                    minRawMeters = rawM;
                }
            }
            if (rank < 0) return new float[] { dist, unit };
            if (minShownRank == 1 && rank == 0) {
                return new float[] { minShownDist, minShownUnit };
            }
            if (rank > minShownRank || minShownDist < 0f) {
                minShownRank = rank;
                minShownUnit = unit;
                minShownDist = dist;
            } else if (unit == minShownUnit) {
                if (dist > minShownDist) {
                    dist = minShownDist;
                } else {
                    minShownDist = dist;
                }
            } else {
                minShownUnit = unit;
                minShownDist = dist;
            }
            return new float[] { dist, unit };
        }

        void clear() {
            state = 0;
            maneuverState = 1;
            road = "";
            destination = "";
            eta = 0;
            timeLeft = 0;
            distLeft = 0;
            distLeftStr = "";
            distLeftUnits = 0;
            distNext = 0;
            hasDistNext = false;
            nextStr = "";
            nextUnits = 0;
            hasNextUnits = false;
            waitingManeuver = false;
            resetMonotonic(-1, -1);
            list = null;
            count = 0;
            visible = false;
            laneIndex = 0;
            laneCount = 0;
            lanesShown = false;
            sourceName = "";
            supports = false;
        }

        void read(int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
            if (g == null) return;
            int prevHead = (list != null && list.length > 0) ? list[0] : -1;
            boolean carriesDistNext = false, carriesNextStr = false, carriesList = false;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), pt = it.getItemParamType();
                if ((pt == 2 || pt == 6) && item == 10 && it.getItemObjCount() > 0) carriesDistNext = true;
                if (pt == 10 && item == 11 && it.getItemObjCount() > 0) carriesNextStr = true;
                if ((pt == 1 || pt == 5) && item == 13) carriesList = true;
            }
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1:
                    case 5: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        switch (item) {
                            case 13:
                                if (v != null) {
                                    list = new int[n];
                                    for (int j = 0; j < n; j++) list[j] = v[j];
                                } else {
                                    list = null;
                                }
                                break;
                            case 14: count = v != null ? v[0] : 0; break;
                            case 16: laneIndex = v != null ? v[0] : 0; break;
                            case 17: laneCount = v != null ? v[0] : 0; break;
                        }
                        break;
                    }
                    case 2:
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        if (item == 7) {
                            distLeft = v[0];
                        } else if (item == 10) {
                            distNext = v[0];
                            hasDistNext = true;
                        }
                        break;
                    }
                    case 3:
                    case 7: {
                        long[] v = n > 0 ? it.getItemLongArray() : null;
                        if (v == null) break;
                        if (item == 5) eta = v[0];
                        else if (item == 6) timeLeft = v[0];
                        break;
                    }
                    case 8: {
                        int[] v = it.getItemIntArray();
                        if (v == null) break;
                        switch (item) {
                            case 1:
                                state = v[0];
                                if (state == 0) {
                                    hasDistNext = false;
                                    waitingManeuver = false;
                                }
                                break;
                            case 2: maneuverState = v[0]; break;
                            case 9: distLeftUnits = v[0]; break;
                            case 12:
                                nextUnits = v[0];
                                hasNextUnits = true;
                                break;
                        }
                        break;
                    }
                    case 9: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        if (item == 15) visible = v[0] != 0;
                        else if (item == 18) lanesShown = v[0] != 0;
                        else if (item == 20) supports = v[0] != 0;
                        break;
                    }
                    case 10: {
                        String[] v = n > 0 ? it.getItemStringArray() : null;
                        if (v == null) break;
                        switch (item) {
                            case 3: road = v[0]; break;
                            case 4: destination = v[0]; break;
                            case 8: distLeftStr = v[0]; break;
                            case 11: nextStr = v[0]; break;
                            case 19: sourceName = v[0]; break;
                        }
                        break;
                    }
                }
            }
            if (carriesNextStr && !carriesDistNext) hasDistNext = false;
            else if (carriesDistNext && !carriesNextStr) nextStr = "";
            else if (carriesList && !carriesDistNext && !carriesNextStr) {
                int newHead = (list != null && list.length > 0) ? list[0] : -1;
                if (prevHead >= 0 && newHead != prevHead) {
                    hasDistNext = false;
                    nextStr = "";
                }
            }
        }
    }

    /** One maneuver */
    static final class Maneuver {
        int index, type, distance, distUnits, side, junction, exit, linkedLane;
        boolean hasDistance, exitSent;
        String description, afterRoad, distStr;
        int[] angles;

        Maneuver(int index, int id, Iap2NotiMsg msg) {
            if (msg == null) return;
            clear();
            read(index, id, msg);
        }

        void clear() {
            index = -1;
            description = "";
            type = 0;
            afterRoad = "";
            distance = 0;
            hasDistance = false;
            distStr = "";
            distUnits = 2;
            side = 0;
            junction = 0;
            angles = null;
            exit = 0;
            exitSent = false;
            linkedLane = 0;
        }

        /**
         * Preserves optional junction geometry (exit angle & road angles) across distance-only
         * partial updates of the same maneuver, resetting them only when the maneuver's type
         * (item 3) or junction kind (item 9) is redefined without new geometry.
         */
        void read(int index, int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
            this.index = index;
            if (g == null) return;
            boolean redefinesManeuver = false, carriesExit = false, carriesAngles = false;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), pt = it.getItemParamType();
                if (pt == 8 && (item == 3 || item == 9)) redefinesManeuver = true;
                if (pt == 1 && item == 11) carriesExit = true;
                if (pt == 1 && item == 10) carriesAngles = true;
            }
            if (redefinesManeuver) {
                if (!carriesExit) {
                    exit = 0;
                    exitSent = false;
                }
                if (!carriesAngles) angles = null;
            }
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 10) {
                            if (v != null) {
                                angles = new int[n];
                                for (int j = 0; j < n; j++) angles[j] = v[j];
                            } else {
                                angles = null;
                            }
                        } else if (item == 11 && v != null) {
                            exit = v[0];
                            exitSent = true;
                        }
                        break;
                    }
                    case 5: {
                        int[] v = it.getItemIntArray();
                        if (item == 12 && v != null) linkedLane = v[0];
                        break;
                    }
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 5 && v != null) {
                            distance = v[0];
                            hasDistance = true;
                        }
                        break;
                    }
                    case 8: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        switch (item) {
                            case 3: type = v[0]; break;
                            case 7: distUnits = v[0]; break;
                            case 8: side = v[0]; break;
                            case 9: junction = v[0]; break;
                        }
                        break;
                    }
                    case 10: {
                        String[] v = it.getItemStringArray();
                        if (v == null) break;
                        switch (item) {
                            case 2: description = v[0]; break;
                            case 4: afterRoad = v[0]; break;
                            case 6: distStr = v[0]; break;
                        }
                        break;
                    }
                }
            }
        }
    }
}
