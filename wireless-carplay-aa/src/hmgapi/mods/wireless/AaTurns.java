package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Calendar;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Android Auto turn-by-turn on the cluster and HUD, for one Android Auto connection.
 *
 * Driven by GPM's navigation callbacks (gpm.navigation_status.*) plus the full 0x8006/0x8007
 * protobuf dump (EV_DUMP -> AaNavState), sending through HMG-API's Cluster API without
 * claiming cluster alive/operationStatus:
 *   - Dynamic native cluster scenes (TURN, EXIT_RIGHT/LEFT, FORK_2, U_TURN, ROUNDABOUT,
 *     RECALCULATING) within GUIDE_WINDOW, with lane-derived intersection side streets and
 *     AR HUD animation (standardTurns, on by default).
 *   - Exact maneuver recovery from 0x8006: keep left/right forks, slight vs normal off-ramps,
 *     on-ramp sharpness, merge side, CW/CCW roundabouts, exit-number fallbacks, and planned
 *     charging-stop durations.
 *   - Observed-velocity 250 ms distance countdown within SMOOTH_MAX_M (never rubber-banding
 *     at red lights and continuing cleanly when time-to-step hits 0 s), with stale distances
 *     cleared on maneuver and route transitions.
 *   - Clean compass restoration (DisplayType 8 with live car heading + NaviBridge stock
 *     compass replay) and trap-frame countdown bar broadcasts to com.mnsoft.navi.
 */
final class AaTurns {
    // AA legacy protocol numbers (Protos.NavigationNextTurnEvent)
    static final int UNKNOWN = 0, DEPART = 1, NAME_CHANGE = 2, SLIGHT_TURN = 3, TURN = 4, SHARP_TURN = 5, U_TURN = 6,
            ON_RAMP = 7, OFF_RAMP = 8, FORK = 9, MERGE = 10, ROUNDABOUT_ENTER = 11, ROUNDABOUT_EXIT = 12,
            ROUNDABOUT_ENTER_AND_EXIT = 13, STRAIGHT = 14, FERRY_BOAT = 16, FERRY_TRAIN = 17, DESTINATION = 19;
    static final int LEFT = 1, RIGHT = 2, UNSPECIFIED = 3;
    static final int METERS = 1, KILOMETERS = 2, KILOMETERS_P1 = 3, MILES = 4, MILES_P1 = 5, FEET = 6, YARDS = 7;
    // the library's extra events and flags
    static final int EV_DEST_NAME = 32, EV_DUMP = 33, EV_LANES = 34, EV_AHEAD = 64, EV_AHEAD_END = 67;
    static final int FLAG_TRIP = 0x100, FLAG_AHEAD = 0x200, FLAG_POINT = 0x800;
    static final byte HUD_LIST = (byte) 0xF2, LANES = (byte) 0xF1;
    /** the car navigation's main-display types (Navigation.DisplayType) */
    static final int TYPE_COMPASS = 8, TYPE_RECALCULATING = 10, TYPE_TURN = 13,
            TYPE_EXIT_RIGHT = 17, TYPE_EXIT_LEFT = 18, TYPE_FORK = 25, TYPE_U_TURN = 31,
            TYPE_ROUNDABOUT_RIGHT = 32, TYPE_ROUNDABOUT_LEFT = 33;
    /** its destination icons: ahead, on the right, on the left */
    static final int DEST_AHEAD = 112, DEST_RIGHT = 113, DEST_LEFT = 114;
    /** how far before a maneuver the car's navigation switches to its turn format and starts the bar (m) */
    static final int GUIDE_WINDOW = 457;
    static final int STATUS_REROUTING = 3;
    static final long REROUTE_SHOW_MS = 1000;

    // Car GPS heading broadcast & NaviBridge IPC broadcast constants
    static final String NAVI_GPS_ACTION = "com.hkmc.telematics.gis.action.NAVI_GPS_CHANGED";
    static final String NAVI_HEADING_EXTRA = "com.hkmc.telematics.gis.extra.HEADING";
    static final String ACTION_PHONE_TBT = "hmgapi.mods.wireless.PHONE_TBT";
    static final String NAVI_PKG = "com.mnsoft.navi";
    static final long HEADING_FRESH_MS = 30000L;
    static final float COMPASS_STEP = 22.5f;
    private static final String ROUNDABOUT_PREFIX = "At the roundabout, ";

    // Smooth approaching-turn countdown parameters
    static final int SMOOTH_MAX_M = 2 * GUIDE_WINDOW;
    static final long SMOOTH_MS = 250, SMOOTH_STALE_MS = 1750;
    static final long PHONE_HEARTBEAT_MS = 2000;
    static final long UNIT_TICK_MS = 1000;

    /** Host tests set testNow >= 0 for deterministic timing; -1 uses monotonic clock. */
    static long testNow = -1;

    static long now() {
        return testNow >= 0 ? testNow : System.nanoTime() / 1000000L;
    }

    final Context context;
    final Cluster cluster;
    final Handler handler;
    /** USM distance-unit cache TTL in ms (set > 0 on the car to avoid frequent Binder IPC). */
    long unitCacheMs;
    private int cachedUsm = -2;
    private long cachedUsmAt = -100000L;

    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            unitTick();
        }
    };

    final Runnable smooth = new Runnable() {
        @Override
        public void run() {
            smoothTick();
        }
    };

    // the next turn
    int event, turnSide, turnAngle = -1, turnNumber, displayType, direction, unit;
    String road, rawRoad;
    float distance = -1f;
    int meters = -1;
    boolean hasTurn, hasDistance;
    boolean navLive;

    // 0x8006 / 0x8007 decoded state
    AaNavState.State navState;
    AaNavState.Position navPosition;
    String navLogged;
    int pendingExactType = -1, pendingStepExit = -1, pendingStepAngle = -1;
    int exactType = -1;
    int rawNextIcon;

    // Roundabout state preserved through ROUNDABOUT_EXIT
    int roundaboutType = -1, roundaboutDir;
    boolean roundaboutCw;

    // Rerouting state
    boolean rerouting, rerouteShown;

    /** Enhanced native cluster & AR HUD scenes (on by default). */
    boolean standardTurns = true;

    // Destination and remaining trip
    String destName, destSent;
    int estPoint;
    int tripMeters = -1, tripSeconds = -1, tripUnit, tripGen, sentGen;
    int lastUsm = -2;

    // HUD turn list & lanes
    int hudType = -1, hudNext;
    boolean hudFresh;
    String hudRoad, laneKey;
    /** Lane arrows belonging to the current maneuver (for intersection side streets). */
    int[] turnLaneArrows;
    int[] pendingLaneArrows;
    long pendingLaneAt = -10000L;

    // Upcoming turns ahead (index 0..2)
    final String[] aheadName = new String[3];
    final int[] aheadEv = new int[3], aheadSide = new int[3], aheadAng = new int[3], aheadExit = new int[3];
    final int[] aheadMeters = new int[3], aheadUnit = new int[3], aheadE3 = new int[3];
    final boolean[] aheadOk = new boolean[3];

    // Smooth countdown state for the current maneuver
    boolean distanceReadyForNext;
    int prevRawMeters = -1;
    long prevRawAt = -10000L;
    int smBaseMeters = -1, smMinMeters = -1, smUnit;
    long smAt;
    float smSpeed;

    // Live car heading for compass restoration & NaviBridge trap-bar state
    private BroadcastReceiver headingReceiver;
    private int headingDeg = -1;
    private long headingAt = -100000L;
    private int lastBroadcastBar = -2;
    private long lastBroadcastAt = -100000L;
    String lastLog;

    AaTurns(Context context, Cluster cluster, Handler handler) {
        this.context = context;
        this.cluster = cluster;
        this.handler = handler;
        watchHeading();
    }

    private int distanceUnit() {
        if (unitCacheMs <= 0) return Car.distanceUnit(context);
        long t = now();
        if (cachedUsm != -2 && t - cachedUsmAt < unitCacheMs) return cachedUsm;
        cachedUsm = Car.distanceUnit(context);
        cachedUsmAt = t;
        return cachedUsm;
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

    private void unwatchHeading() {
        if (headingReceiver == null || context == null) return;
        try {
            context.unregisterReceiver(headingReceiver);
        } catch (Throwable ignored) {
        }
        headingReceiver = null;
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
            i.putExtra("source", "aa");
            i.putExtra("active", active);
            i.putExtra("bar", active ? bar : -1);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    // ---- from the AA library

    void nextTurn(String str, int side, int ev, int angle, int number) {
        if (ev == EV_DUMP) {
            Log.d("NAVDUMP", String.valueOf(str));
            onDump(str);
            hmgapi.hook.ProjectionHooks.navigationDump(str);
            return;
        }
        if (ev == EV_LANES) {
            sendLanes(str);
            return;
        }
        if (ev == EV_DEST_NAME) {
            if (str == null || str.length() == 0) {
                destName = "";
                sendDestName();
                destName = null;
                destSent = null;
                return;
            }
            if (number >= 0) estPoint = number;
            destName = str;
            if (!destText().equals(destSent)) sendDestName();
            return;
        }
        if (ev == EV_AHEAD_END) {
            int n = Math.max(0, Math.min(number, 3));
            if (n == 0) {
                hudNext = 0;
                hudFresh = false;
                for (int k = 0; k < 3; k++) aheadOk[k] = false;
                if (navLive && hasTurn) sendHudList();
            } else {
                sendAhead(n);
                if (navLive && hasTurn) sendHudList();
            }
            return;
        }
        if (ev >= EV_AHEAD && ev < EV_AHEAD_END) {
            int k = ev - EV_AHEAD;
            if (k == 0) {
                for (int j = 0; j < 3; j++) aheadOk[j] = false;
            }
            aheadName[k] = str == null ? "" : cleanRoadText(str);
            aheadSide[k] = side;
            aheadAng[k] = angle;
            aheadEv[k] = number & 0xff;
            aheadExit[k] = (number >> 8) & 0xff;
            aheadOk[k] = false;
            return;
        }
        if (rerouting) {
            rerouting = false;
            rerouteShown = false;
            handler.removeCallbacks(rerouteShow);
        }
        String cleaned = cleanRoadText(str);
        int matchedExact = consumeExactType(ev, side);
        if (matchedExact >= 0 && side == UNSPECIFIED) {
            int exactSide = AaNavState.side(matchedExact);
            if (exactSide == AaNavState.SIDE_LEFT) side = LEFT;
            else if (exactSide == AaNavState.SIDE_RIGHT) side = RIGHT;
        }
        if (ev != ROUNDABOUT_ENTER) {
            if (isRoundabout(ev) && angle < 0 && pendingStepAngle >= 0) angle = pendingStepAngle;
            if (isRoundabout(ev) && number <= 0 && pendingStepExit > 0) number = pendingStepExit;
        }
        pendingStepAngle = -1;
        pendingStepExit = -1;

        if (ev == ROUNDABOUT_ENTER && !standardTurns) {
            // Legacy GpmNaviTBTManager ignores ROUNDABOUT_ENTER; preserve any prior roundaboutType.
            event = ev;
            turnSide = side;
            turnAngle = angle;
            turnNumber = number;
            logTurn();
            return;
        }

        boolean first = !hasTurn && rawRoad == null;
        boolean same = hasTurn && ev == event && side == turnSide && angle == turnAngle
                && number == turnNumber && eq(str, rawRoad);

        if (!same) {
            hudFresh = false;
            if (distanceReadyForNext) {
                distanceReadyForNext = false;
            } else {
                // Clear the previous maneuver's distance so a new turn miles away never flashes
                // the completed turn's final 20 ft or premature near-turn scene.
                distance = -1f;
                meters = -1;
                unit = 0;
                hasDistance = false;
            }
            resetSmooth();
            if (pendingLaneArrows != null && now() - pendingLaneAt <= 500L) {
                turnLaneArrows = pendingLaneArrows;
                pendingLaneArrows = null;
            } else {
                turnLaneArrows = null;
            }
        }
        if (matchedExact >= 0) exactType = matchedExact;
        else if (!same) exactType = -1;

        hasTurn = true;
        rawRoad = str;
        road = cleaned;
        event = ev;
        turnSide = side;
        turnAngle = angle;
        turnNumber = number;
        if (!isRoundabout(ev)) {
            roundaboutType = -1;
            roundaboutCw = false;
        }

        switch (ev) {
            case DEPART:
                if (first) {
                    if (navLive) cluster.destinationName(0, road);
                    distance = -1f;
                    meters = -1;
                    hasDistance = false;
                    displayType = 2;
                    direction = 0;
                } else {
                    if (navLive) cluster.crossroadName(road);
                    setTurnType(ev, turnSide);
                }
                break;
            case NAME_CHANGE: case SLIGHT_TURN: case TURN: case SHARP_TURN: case STRAIGHT: case MERGE:
                if (navLive) cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case U_TURN:
                if (side == UNSPECIFIED) turnSide = LEFT;
                if (navLive) cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case ON_RAMP: case OFF_RAMP: case FORK: case FERRY_BOAT: case FERRY_TRAIN:
                if (navLive) cluster.crossroadName(road);
                setRampType(ev, turnSide);
                break;
            case ROUNDABOUT_ENTER: case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                if (navLive) cluster.crossroadName(road);
                setRoundaboutType(ev, turnSide, turnAngle, turnNumber);
                break;
            case DESTINATION:
                if (navLive) cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case UNKNOWN:
                displayType = 65;   // straight: 64 is the cluster's spinner
                direction = 0;
                break;
            default:
                displayType = 65;
                direction = 0;
                break;
        }
        applyExact();
        logTurn();
        sendTurn();
    }

    private void onDump(String line) {
        try {
            AaNavState.Dump d = AaNavState.parse(line);
            if (d == null) return;
            if (d.state != null) {
                navState = d.state;
                AaNavState.Step s = d.state.step();
                if (s != null) {
                    pendingExactType = s.type;
                    pendingStepExit = s.exitNumber;
                    pendingStepAngle = s.exitAngle;
                }
                if (d.state.steps.size() > 1) {
                    AaNavState.Step nextStep = d.state.steps.get(1);
                    int[] leg = AaNavState.legacy(nextStep.type);
                    rawNextIcon = aheadType(leg[0], leg[1], nextStep.exitAngle, nextStep.exitNumber);
                } else {
                    rawNextIcon = 0;
                }
                if (standardTurns && destName != null && destName.length() > 0 && !destText().equals(destSent)) {
                    sendDestName();
                }
                String sum = AaNavState.summary(d);
                if (!sum.equals(navLogged)) {
                    navLogged = sum;
                    Log.d("AANAV", sum);
                }
            } else if (d.position != null) {
                navPosition = d.position;
            }
        } catch (AaNavState.ParseException ignored) {
        }
    }

    private int consumeExactType(int ev, int side) {
        int exact = pendingExactType;
        pendingExactType = -1;
        if (exact < 0) return -1;
        int[] leg = AaNavState.legacy(exact);
        if (leg[0] != ev) return -1;
        if (side != 0 && side != UNSPECIFIED && leg[1] != UNSPECIFIED && leg[1] != side && !isRoundabout(ev)) return -1;
        return exact;
    }

    static boolean isKeep(int exact) {
        return exact == AaNavState.M_KEEP_LEFT || exact == AaNavState.M_KEEP_RIGHT;
    }

    static boolean isClockwiseExact(int exact) {
        return exact == AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW
                || exact == AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
                || exact == AaNavState.M_ROUNDABOUT_ENTER_CW
                || exact == AaNavState.M_ROUNDABOUT_EXIT_CW;
    }

    void applyExact() {
        if (!standardTurns || exactType < 0) return;
        if (isKeep(exactType)) {
            boolean r = exactType == AaNavState.M_KEEP_RIGHT;
            displayType = r ? 173 : 174;
            direction = 0;
        } else if (event == MERGE && (exactType == AaNavState.M_MERGE_LEFT || exactType == AaNavState.M_MERGE_RIGHT)) {
            boolean r = exactType == AaNavState.M_MERGE_RIGHT;
            displayType = r ? 66 : 71;
            direction = r ? 45 : 315;
        } else if (isRoundabout(event)) {
            if (event != ROUNDABOUT_EXIT) roundaboutCw = isClockwiseExact(exactType);
            boolean cw = isClockwiseExact(exactType) || (event == ROUNDABOUT_EXIT && roundaboutCw);
            if (cw && displayType >= 96 && displayType <= 107) {
                displayType += 96;   // clockwise LHT roundabout family 192..203
                if (event != ROUNDABOUT_EXIT) roundaboutType = displayType;
            }
        }
    }

    int[] standardNow(int m) {
        boolean keep = exactType >= 0 && isKeep(exactType);
        int effSide = turnSide;
        if (keep) effSide = exactType == AaNavState.M_KEEP_RIGHT ? RIGHT : LEFT;
        int[] n = standard(keep ? FORK : event, effSide, displayType, direction, m);
        if (exactType >= 0 && event == ON_RAMP && n[0] == TYPE_TURN && (effSide == LEFT || effSide == RIGHT)) {
            boolean r = effSide == RIGHT;
            switch (exactType) {
                case AaNavState.M_ON_RAMP_NORMAL_LEFT: case AaNavState.M_ON_RAMP_NORMAL_RIGHT:
                    n[1] = r ? 90 : 270;
                    break;
                case AaNavState.M_ON_RAMP_SHARP_LEFT: case AaNavState.M_ON_RAMP_SHARP_RIGHT:
                case AaNavState.M_ON_RAMP_U_TURN_LEFT: case AaNavState.M_ON_RAMP_U_TURN_RIGHT:
                    n[1] = r ? 135 : 225;
                    break;
                default:
                    break;
            }
        }
        return n;
    }

    /** Strips verbose "At the roundabout, " prefix so exit instructions fit on narrow clusters. */
    static String cleanRoadText(String s) {
        if (s == null || s.length() <= ROUNDABOUT_PREFIX.length()) return s;
        if (!s.regionMatches(true, 0, ROUNDABOUT_PREFIX, 0, ROUNDABOUT_PREFIX.length())) return s;
        String rest = s.substring(ROUNDABOUT_PREFIX.length()).trim();
        return rest.isEmpty() ? s : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    void logTurn() {
        String s = "[wireless-carplay-aa] AA turn: event " + event + " side " + turnSide + " angle " + turnAngle
                + " number " + turnNumber + (exactType >= 0 ? " exact " + exactType : "")
                + " -> type " + displayType + " dir " + direction;
        if (standardTurns) {
            int[] n = standardNow(GUIDE_WINDOW);
            s += ", near: type " + n[0] + " dir " + n[1];
        }
        if (s.equals(lastLog)) return;
        lastLog = s;
        Log.i("HMG-API", s);
    }

    void nextTurnDistance(int meters, int seconds, int e3, int unitFlags) {
        if ((unitFlags & FLAG_AHEAD) != 0) {
            int k = (unitFlags >> 10) & 3;
            if (k > 2) return;
            aheadMeters[k] = meters;
            aheadE3[k] = e3;
            aheadUnit[k] = unitFlags & 0xff;
            aheadOk[k] = true;
            return;
        }
        if ((unitFlags & FLAG_TRIP) != 0) {
            if ((unitFlags & FLAG_POINT) != 0) {
                int p = (unitFlags >> 12) & 15;
                estPoint = p > 4 ? 0 : p;
            }
            tripMeters = meters;
            tripSeconds = seconds;
            tripUnit = unitFlags & 0xff;
            tripGen++;
            sendRemain();
            return;
        }
        hasDistance = true;
        int rawU = unitFlags & 0xff;
        setDistance(meters, e3, rawU, false);
        if (!hasTurn || rerouting) {
            distanceReadyForNext = true;
            if (!hasTurn) return;
        } else {
            distanceReadyForNext = false;
        }
        refreshTurnType();
        sendTurn();
        if (standardTurns && !rerouting) smoothFrom(meters, seconds, rawU);
    }

    private void refreshTurnType() {
        switch (event) {
            case ON_RAMP: case OFF_RAMP: case FORK: case FERRY_BOAT: case FERRY_TRAIN:
                setRampType(event, turnSide);
                break;
            case ROUNDABOUT_ENTER: case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                setRoundaboutType(event, turnSide, turnAngle, turnNumber);
                break;
            default:
                setTurnType(event, turnSide);
                break;
        }
        applyExact();
    }

    // ---- Smooth 250 ms approaching-turn countdown

    private void resetSmooth() {
        handler.removeCallbacks(smooth);
        prevRawMeters = -1;
        prevRawAt = -10000L;
        smBaseMeters = -1;
        smMinMeters = -1;
        smSpeed = 0f;
    }

    void smoothFrom(int m, int seconds, int u) {
        handler.removeCallbacks(smooth);
        long t = now();
        float v = 0f;
        if (prevRawMeters >= 0 && t > prevRawAt && t - prevRawAt <= 3500L) {
            int dm = prevRawMeters - m;
            if (dm <= 0) {
                // Vehicle is stationary (e.g. stopped at a red light): hold steady, never rubber-band!
                v = 0f;
            } else {
                v = Math.min(55f, dm * 1000f / (t - prevRawAt));
            }
        } else if (seconds > 0 && m > 0 && m <= SMOOTH_MAX_M) {
            v = Math.min(45f, m / (float) seconds);
        }
        prevRawMeters = m;
        prevRawAt = t;

        if (m <= 0 || m > SMOOTH_MAX_M || v < 1.0f) {
            smBaseMeters = -1;
            return;
        }
        if (smMinMeters < 0 || m - smMinMeters > 15) smMinMeters = m;
        else smMinMeters = Math.min(smMinMeters, m);
        smBaseMeters = m;
        smUnit = u;
        smSpeed = v;
        smAt = t;
        handler.postDelayed(smooth, SMOOTH_MS);
    }

    void smoothTick() {
        if (smBaseMeters < 0 || !navLive || rerouteShown || !hasTurn) return;
        long dt = now() - smAt;
        if (dt > SMOOTH_STALE_MS) return;   // Phone went quiet: hold the last displayed distance
        int est = Math.max(0, Math.round(smBaseMeters - smSpeed * dt / 1000f));
        if (smMinMeters >= 0) est = Math.min(est, smMinMeters);
        smMinMeters = est;

        float beforeDist = distance;
        int beforeMeters = meters;
        setDistance(est, e3For(est, smUnit), smUnit, true);
        boolean barMoved = standardTurns && (beforeMeters * 10 / GUIDE_WINDOW) != (est * 10 / GUIDE_WINDOW);
        if (distance != beforeDist || barMoved) {
            refreshTurnType();
            sendTurn();
        }
        if (est > 0) handler.postDelayed(smooth, SMOOTH_MS);
    }

    static int e3For(int m, int u) {
        switch (u) {
            case FEET: return m * 3281;
            case YARDS: return m * 1094;
            case MILES: case MILES_P1: return m * 1000 / 1609;
            case KILOMETERS: case KILOMETERS_P1: return m;
            default: return m * 1000;
        }
    }

    void status(int s) {
        if (s == 1 || s == STATUS_REROUTING) {
            boolean becameLive = !navLive;
            navLive = true;
            if (s == STATUS_REROUTING) {
                reroute();
            } else if (rerouting) {
                endReroute();
            }
            if (becameLive) {
                if (destName != null && destName.length() > 0) sendDestName();
                if (hasTurn) {
                    if (event == DEPART && !hasDistance) cluster.destinationName(0, road);
                    else if (road != null) cluster.crossroadName(road);
                    sendTurn();
                }
                if (tripMeters >= 0) sendRemain();
            }
            return;
        }
        rerouting = false;
        rerouteShown = false;
        handler.removeCallbacks(rerouteShow);
        handler.removeCallbacks(tick);
        resetSmooth();
        navLive = false;
        destName = null;
        destSent = null;
        tripMeters = -1;
        tripSeconds = -1;
        estPoint = 0;
        release();
    }

    void reroute() {
        if (!standardTurns || rerouting) return;
        rerouting = true;
        handler.removeCallbacks(rerouteShow);
        handler.postDelayed(rerouteShow, REROUTE_SHOW_MS);
    }

    final Runnable rerouteShow = new Runnable() {
        @Override
        public void run() {
            if (!rerouting || !navLive) return;
            rerouteShown = true;
            cluster.tbtInfo(TYPE_RECALCULATING, 65535, 0f, 0f, -3, 0, 15, 0, -1);
            notifyNaviBridge(true, 0);
        }
    };

    void endReroute() {
        boolean shown = rerouteShown;
        rerouting = false;
        rerouteShown = false;
        handler.removeCallbacks(rerouteShow);
        if (shown && hasTurn) sendTurn();
    }

    /** Resets current route/maneuver state (native focus takeover or route end). */
    void stop() {
        resetSmooth();
        hasTurn = false;
        hasDistance = false;
        distanceReadyForNext = false;
        event = 0;
        road = null;
        rawRoad = null;
        turnSide = 0;
        turnAngle = -1;
        turnNumber = 0;
        exactType = -1;
        pendingExactType = -1;
        pendingStepExit = -1;
        pendingStepAngle = -1;
        rawNextIcon = 0;
        unit = 0;
        distance = -1f;
        meters = -1;
        displayType = 0;
        direction = 0;
        roundaboutType = -1;
        roundaboutCw = false;
        turnLaneArrows = null;
        pendingLaneArrows = null;
        for (int k = 0; k < 3; k++) aheadOk[k] = false;
        rerouting = false;
        rerouteShown = false;
        handler.removeCallbacks(rerouteShow);
    }

    /** Connection ended: release the cluster cleanly and unregister receivers. */
    void end() {
        handler.removeCallbacks(tick);
        resetSmooth();
        if (navLive) status(2);
        else notifyNaviBridge(false, -1);
        unwatchHeading();
    }

    // ---- the next turn

    void sendTurn() {
        if (!navLive || !hasTurn || rerouteShown) return;
        int type = displayType, dir = direction, bar = 32, barErr = 0, sides = 65535;
        int trapBar = 0;
        if (distance < 0f) {
            bar = 254;
            barErr = -2;
            if (displayType < 64) displayType = type = 2;
        } else if (standardTurns) {
            int[] s = standardNow(meters);
            type = s[0];
            dir = s[1];
            bar = s[2];
            if (type < 64) {
                sides = (type == TYPE_TURN && turnLaneArrows != null)
                        ? laneSideStreets(dir, turnLaneArrows) : 0;
                if (type != 2 && type != 3 && type != TYPE_RECALCULATING) trapBar = bar;
            }
        }
        cluster.tbtInfo(type, sides, dir, distance, 0, 0, unit, bar, barErr);
        notifyNaviBridge(true, trapBar);
        sendHudList();
        sendHudDistance();
    }

    static int[] standard(int ev, int side, int icon, int iconDir, int m) {
        if (ev == DESTINATION) {
            if (m >= 0 && m < 20) return new int[] { 3, 0, 0 };   // ARRIVED
            int d = side == RIGHT ? DEST_RIGHT : side == LEFT ? DEST_LEFT : DEST_AHEAD;
            return new int[] { d, 0, 0 };
        }
        if (m < 0 || m > GUIDE_WINDOW) return new int[] { icon, iconDir, 0 };
        int bar = Math.max(1, Math.min(9, m * 10 / GUIDE_WINDOW)) * 10;
        boolean r = side == RIGHT, l = side == LEFT;
        switch (ev) {
            case STRAIGHT:
                return new int[] { TYPE_TURN, 0, bar };
            case MERGE:
                return new int[] { TYPE_TURN, r ? 45 : l ? 315 : 0, bar };
            case NAME_CHANGE:
                return r || l ? new int[] { TYPE_TURN, r ? 90 : 270, bar } : new int[] { TYPE_TURN, 0, bar };
            case SLIGHT_TURN:
                if (r || l) return new int[] { TYPE_TURN, r ? 45 : 315, bar };
                break;
            case TURN:
                if (r || l) return new int[] { TYPE_TURN, r ? 90 : 270, bar };
                break;
            case SHARP_TURN:
                if (r || l) return new int[] { TYPE_TURN, r ? 135 : 225, bar };
                break;
            case U_TURN:
                return new int[] { TYPE_U_TURN, r ? 90 : 270, bar };
            case ON_RAMP:
                return new int[] { TYPE_TURN, r ? 45 : l ? 315 : 0, bar };
            case FORK:
                if (r || l) return new int[] { TYPE_FORK, r ? 90 : 270, bar };
                break;
            case OFF_RAMP:
                if (r || l) return new int[] { r ? TYPE_EXIT_RIGHT : TYPE_EXIT_LEFT, r ? 90 : 270, bar };
                break;
            case ROUNDABOUT_ENTER: case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                if (icon == 65) return new int[] { TYPE_TURN, 0, bar };
                return new int[] { clockwiseIcon(icon) ? TYPE_ROUNDABOUT_LEFT : TYPE_ROUNDABOUT_RIGHT, iconDir, bar };
            default:
                break;
        }
        return new int[] { icon, iconDir, bar };
    }

    void setDistance(int meters, int e3, int u) {
        setDistance(meters, e3, u, false);
    }

    void setDistance(int meters, int e3, int u, boolean interpolated) {
        this.meters = meters;
        int usm = distanceUnit();
        if (meters >= 0) {
            if (usm == Car.DISTANCE_KM) {
                if (u >= MILES || interpolated) {
                    if (meters < 1000) {
                        u = METERS;
                    } else {
                        e3 = meters;
                        u = KILOMETERS;
                    }
                }
            } else if (usm == Car.DISTANCE_MILES) {
                if (meters >= 161) {
                    if (u < MILES || interpolated) {
                        e3 = meters * 1000 / 1609;
                        u = MILES_P1;
                    }
                } else if ((u != FEET && u != YARDS) || interpolated) {
                    e3 = meters * 3281;
                    u = FEET;
                }
            }
        }
        distance = (float) (e3 / 1000.0d);
        if (u == METERS) {
            if (meters >= 1000) {
                distance = (float) (meters / 1000.0d);
                unit = Cluster.UNIT_KM;
            } else {
                distance = meters;
                unit = Cluster.UNIT_M;
            }
        } else if (u == KILOMETERS || u == KILOMETERS_P1) {
            unit = Cluster.UNIT_KM;
        } else if (u == MILES || u == MILES_P1) {
            unit = Cluster.UNIT_MI;
        } else if (u == FEET) {
            unit = Cluster.UNIT_FT;
        } else if (u == YARDS) {
            unit = Cluster.UNIT_YD;
        } else {
            unit = 15;
        }
    }

    void setTurnType(int ev, int side) {
        switch (ev) {
            case DESTINATION:
                if (meters >= 0 && meters < 20) {
                    displayType = 3;
                } else {
                    displayType = side == RIGHT ? DEST_RIGHT : side == LEFT ? DEST_LEFT : DEST_AHEAD;
                }
                direction = 0;
                break;
            case STRAIGHT:
                displayType = 65;
                direction = 0;
                break;
            case MERGE:
                if (side == RIGHT || side == LEFT) {
                    set(side, 66, 45, 71, 315);
                } else {
                    displayType = 65;
                    direction = 0;
                }
                break;
            case SLIGHT_TURN:
                set(side, 66, 30, 71, 330);
                break;
            case TURN:
                set(side, 67, 90, 70, 270);
                break;
            case SHARP_TURN:
                set(side, 68, 135, 69, 225);
                break;
            case U_TURN:
                displayType = side == RIGHT ? 73 : 72;
                direction = 270;
                break;
            case NAME_CHANGE:
                if (side == RIGHT || side == LEFT) {
                    set(side, 67, 90, 70, 270);
                } else {
                    displayType = 65;
                    direction = 0;
                }
                break;
            default:
                break;
        }
    }

    private void set(int side, int rType, int rDir, int lType, int lDir) {
        if (side == RIGHT) {
            displayType = rType;
            direction = rDir;
        } else if (side == LEFT) {
            displayType = lType;
            direction = lDir;
        } else {
            displayType = 65;
            direction = 0;
        }
    }

    void setRampType(int ev, int side) {
        if (ev == ON_RAMP) {
            if (side == RIGHT) {
                displayType = 173;
                direction = 30;
            } else if (side == LEFT) {
                displayType = 174;
                direction = 330;
            } else {
                displayType = 172;
                direction = 0;
            }
        } else if (ev == OFF_RAMP) {
            if (side == RIGHT) {
                displayType = 81;
                direction = 30;
            } else if (side == LEFT) {
                displayType = 82;
                direction = 330;
            } else {
                displayType = 86;
                direction = 0;
            }
        } else if (ev == FORK) {
            displayType = side == RIGHT ? 173 : side == LEFT ? 174 : 172;
            direction = 0;
        } else if (ev == FERRY_BOAT || ev == FERRY_TRAIN) {
            displayType = 176;
            direction = 0;
        }
    }

    void setRoundaboutType(int ev, int side, int a, int exitNum) {
        if (ev == ROUNDABOUT_ENTER) {
            if (roundaboutType >= 0) {
                displayType = roundaboutType;
                direction = roundaboutDir;
                return;
            }
            int[] r = null;
            if (navState != null && navState.steps.size() > 1) {
                AaNavState.Step s1 = navState.steps.get(1);
                if (s1.type == AaNavState.M_ROUNDABOUT_EXIT_CCW || s1.type == AaNavState.M_ROUNDABOUT_EXIT_CW) {
                    r = roundaboutWithFallback(s1.exitAngle, s1.exitNumber);
                }
            }
            if (r == null || r[0] == 65) r = new int[] { 96, 0 };
            displayType = r[0];
            direction = r[1];
            roundaboutType = r[0];
            roundaboutDir = r[1];
            return;
        }
        if (ev == ROUNDABOUT_EXIT) {
            if (roundaboutType >= 0) {
                displayType = roundaboutType;
                direction = roundaboutDir;
            } else {
                int[] r = roundaboutWithFallback(a, exitNum);
                displayType = r[0];
                direction = r[1];
            }
            return;
        }
        int[] r = roundaboutWithFallback(a, exitNum);
        displayType = r[0];
        direction = r[1];
        roundaboutType = r[0] == 65 ? -1 : r[0];
        roundaboutDir = r[1];
    }

    static int[] roundaboutWithFallback(int a, int exitNum) {
        int[] r = roundabout(a);
        if (r[0] != 65 || a >= 0) return r;
        if (exitNum == 1) return new int[] { 99, 90 };
        if (exitNum == 2) return new int[] { 96, 0 };
        if (exitNum == 3) return new int[] { 105, 270 };
        if (exitNum >= 4 && exitNum < 255) return new int[] { 102, 180 };
        return r;
    }

    static boolean clockwiseIcon(int type) {
        return type >= 192 && type <= 203;
    }

    static int[] roundabout(int a) {
        if (a == 180) return new int[] { 96, 0 };
        if (a >= 135 && a < 180) return new int[] { 97, 45 };
        if (a >= 90 && a < 135) return new int[] { 99, 90 };
        if (a >= 45 && a < 90) return new int[] { 100, 135 };
        if ((a >= 0 && a < 45) || (a > 315 && a <= 360)) return new int[] { 102, 180 };
        if (a > 270 && a <= 315) return new int[] { 104, 225 };
        if (a > 225 && a <= 270) return new int[] { 105, 270 };
        if (a > 180 && a <= 225) return new int[] { 107, 315 };
        return new int[] { 65, 0 };
    }

    // ---- HUD

    void sendHudList() {
        int type = displayType;
        if (type < 64) return;
        int next = 0;
        if (aheadOk[0]) {
            next = aheadType(aheadEv[0], aheadSide[0], aheadAng[0], aheadExit[0]);
            hudFresh = true;
        } else if (hudFresh) {
            next = hudNext;
        } else if (rawNextIcon != 0) {
            next = rawNextIcon;
        }
        String r = road == null ? "" : road;
        if (hudType == type && hudNext == next && r.equals(hudRoad)) return;
        byte[] name;
        try {
            name = r.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(name.length, 78);
        byte[] f = new byte[n + 5];
        f[0] = HUD_LIST;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(name, 0, f, 5, n);
        cluster.turnListNew(f);
        hudType = type;
        hudNext = next;
        hudRoad = r;
    }

    void sendHudDistance() {
        float d = distance;
        int u = unit;
        if (d < 0f || u < 0 || u > 4) return;
        int whole = Math.min((int) d, 4095);
        int tenths = 0;
        if (u == Cluster.UNIT_KM || u == Cluster.UNIT_MI) tenths = Math.max(0, Math.min(9, (int) ((d - whole) * 10f + 0.5f)));
        cluster.tbtSubInfo2C(u, tenths, whole, 0, 0, 0, 0, 0, 0);
    }

    void hudBlank() {
        laneKey = "";
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] lanes = new byte[81];
        lanes[0] = LANES;
        cluster.laneInfo(lanes);
        hudType = -1;
        hudNext = 0;
        hudFresh = false;
        hudRoad = null;
        cluster.turnListNew(new byte[] { HUD_LIST });
        cluster.turnListNew(new byte[] { HUD_LIST });
    }

    /**
     * Releases phone guidance cleanly back to the compass: clears text/lists/HUD, sends
     * DisplayType 8 (COMPASS) instead of DisplayType 0 (which triggers the 4-bar loading
     * dance), and signals NaviBridge in com.mnsoft.navi to replay its native compass frame.
     */
    void release() {
        stop();
        cluster.destinationName(0, "");
        cluster.crossroadName("");
        cluster.turnList(new Cluster.Turn[0]);
        float deg = (headingDeg >= 0 && now() - headingAt <= HEADING_FRESH_MS) ? clusterCompassDeg(headingDeg) : 0f;
        cluster.tbtInfo(TYPE_COMPASS, 0, deg, 0f, 0, 0, 0, 0, -1);
        cluster.tbtSubInfo(0f, -3, 15, -1, -1, -1, -1, deg, 0, -1);
        hudBlank();
        notifyNaviBridge(false, -1);
    }

    void sendDestName() {
        if (destName == null) return;
        String t = destText();
        destSent = t;
        cluster.destinationName(0, t);
    }

    String destText() {
        if (!standardTurns || destName == null || destName.length() == 0 || navState == null
                || navState.destinations.isEmpty()) return destName;
        AaNavState.Destination d = navState.destinations.get(0);
        if (!d.station || d.chargeSeconds <= 0) return destName;
        return destName + " - " + Math.max(1, (d.chargeSeconds + 30) / 60) + " min charge";
    }

    // ---- lanes & lane-derived intersection side streets

    void sendLanes(String s) {
        if (s == null) s = "";
        int n = 0;
        if (s.length() >= 2) {
            n = hexByte(s, 0);
            if (n < 0 || n > 8) return;
        }
        byte[] f = new byte[81];
        f[0] = LANES;
        f[1] = (byte) n;
        int[] arrows = new int[n * 3];
        int arrowCount = 0;
        for (int k = 0; k < n; k++) {
            int at = k * 8 + 2;
            int b = hexByte(s, at);
            if (b < 0) return;
            f[k + 3] = (byte) b;
            for (int j = 0; j < 3; j++) {
                b = hexByte(s, at + 2 + j * 2);
                if (b < 0) return;
                f[k * 3 + 19 + j] = (byte) b;
                if (b >= 1 && b <= 8) arrows[arrowCount++] = b;
            }
        }
        int[] trimmed = arrowCount > 0 ? new int[arrowCount] : null;
        if (trimmed != null) System.arraycopy(arrows, 0, trimmed, 0, arrowCount);
        if (hasTurn) {
            turnLaneArrows = trimmed;
        } else {
            pendingLaneArrows = trimmed;
            pendingLaneAt = now();
        }
        if (!s.equals(laneKey)) {
            cluster.laneInfo(f);
            laneKey = s;
            if (navLive && hasTurn && standardTurns && distance >= 0f && meters >= 0 && meters <= GUIDE_WINDOW) {
                sendTurn();
            }
        }
    }

    /**
     * Builds the 16-bit sideStreet mask (one bit per 22.5 degrees) around a TURN arrow from
     * the intersection's lane arrows. Requires at least one arrow matching the turn's side
     * (within 45 degrees) and at least one arrow going elsewhere; otherwise returns 0.
     */
    static int laneSideStreets(int turnDir, int[] laneArrows) {
        if (laneArrows == null || laneArrows.length == 0) return 0;
        boolean matchedTurn = false;
        int otherBits = 0;
        for (int a : laneArrows) {
            int deg = laneArrowDeg(a);
            if (deg < 0 || deg == 180) continue;   // U-turn arrows add no road arm
            if (sameTurnRoad(turnDir, deg)) {
                matchedTurn = true;
            } else {
                otherBits |= roadBit(deg);
            }
        }
        if (!matchedTurn || otherBits == 0) return 0;
        return roadBit(turnDir) | otherBits;
    }

    private static boolean sameTurnRoad(int turnDir, int deg) {
        if (turnDir == 0) return deg == 0;
        boolean turnRight = turnDir > 0 && turnDir < 180;
        boolean degRight = deg > 0 && deg < 180;
        if (turnRight != degRight || deg == 0) return false;
        int diff = Math.abs(turnDir - deg);
        return diff <= 45;
    }

    /** Cluster 0xF1 single-arrow code (1..8) -> clockwise angle in degrees (-1 unknown). */
    static int laneArrowDeg(int code) {
        switch (code & 0xff) {
            case 1: return 0;
            case 2: return 45;
            case 3: return 90;
            case 4: return 135;
            case 5: return 180;
            case 6: return 225;
            case 7: return 270;
            case 8: return 315;
            default: return -1;
        }
    }

    /** Maps a clockwise road angle (0..359) to its 22.5-degree sideStreet bit (0 for behind). */
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

    static int hexByte(String s, int at) {
        if (at + 1 >= s.length()) return -1;
        int hi = hex(s.charAt(at)), lo = hex(s.charAt(at + 1));
        return hi < 0 || lo < 0 ? -1 : hi << 4 | lo;
    }

    static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // ---- the turns ahead (cluster turn list)

    static int aheadType(int ev, int side, int angle) {
        return aheadType(ev, side, angle, -1);
    }

    static int aheadType(int ev, int side, int angle, int exitNum) {
        boolean r = side == RIGHT, l = side == LEFT;
        switch (ev) {
            case ROUNDABOUT_ENTER: case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                return roundaboutWithFallback(angle, exitNum)[0];
            case SLIGHT_TURN: return r ? 66 : l ? 71 : 65;
            case TURN: case NAME_CHANGE: return r ? 67 : l ? 70 : 65;
            case SHARP_TURN: return r ? 68 : l ? 69 : 65;
            case MERGE: return r ? 66 : l ? 71 : 65;
            case U_TURN: return r ? 73 : 72;
            case ON_RAMP: case FORK: return r ? 173 : l ? 174 : 172;
            case OFF_RAMP: return r ? 81 : l ? 82 : 86;
            case FERRY_BOAT: case FERRY_TRAIN: return 176;
            case DESTINATION: return r ? DEST_RIGHT : l ? DEST_LEFT : DEST_AHEAD;
            default: return 65;
        }
    }

    static boolean isRoundabout(int ev) {
        return ev == ROUNDABOUT_ENTER || ev == ROUNDABOUT_EXIT || ev == ROUNDABOUT_ENTER_AND_EXIT;
    }

    void sendAhead(int n) {
        int count = 0;
        for (int k = 0; k < n; k++) if (aheadOk[k]) count++;
        if (count == 0) return;
        int usm = distanceUnit();
        Cluster.Turn[] list = new Cluster.Turn[count];
        int i = 0;
        for (int k = 0; k < n && i < count; k++) {
            if (!aheadOk[k]) continue;
            Cluster.Turn t = new Cluster.Turn();
            t.type = aheadType(aheadEv[k], aheadSide[k], aheadAng[k], aheadExit[k]);
            t.direction = isRoundabout(aheadEv[k]) ? roundaboutWithFallback(aheadAng[k], aheadExit[k])[1] : Math.max(aheadAng[k], 0);
            t.traffic = 0;
            t.no = i + 1;
            t.name = aheadName[k] == null ? "" : aheadName[k];
            t.exitNumber = aheadExit[k] == 255 || aheadExit[k] <= 0 ? "" : String.valueOf(aheadExit[k]);
            int m = aheadMeters[k], e3 = aheadE3[k], u = aheadUnit[k];
            int cu = Cluster.UNIT_M;
            float d = m;
            if (e3 >= 0) {
                if (u == KILOMETERS || u == KILOMETERS_P1) { cu = Cluster.UNIT_KM; d = e3 / 1000f; }
                else if (u == MILES || u == MILES_P1) { cu = Cluster.UNIT_MI; d = e3 / 1000f; }
                else if (u == FEET) { cu = Cluster.UNIT_FT; d = e3 / 1000f; }
                else if (u == YARDS) { cu = Cluster.UNIT_FT; d = e3 / 1000f * 3f; }
            }
            if (usm == Car.DISTANCE_KM) {
                if (m < 1000) { cu = Cluster.UNIT_M; d = m; }
                else { cu = Cluster.UNIT_KM; d = m / 1000f; }
            } else if (usm == Car.DISTANCE_MILES) {
                if (m < 161) { cu = Cluster.UNIT_FT; d = m * 3.28084f; }
                else { cu = Cluster.UNIT_MI; d = m / 1609f; }
            }
            t.distance = Math.max(0f, Math.min(65535f, d));
            t.unit = cu;
            list[i++] = t;
        }
        cluster.turnList(list);
        if (aheadOk[0]) {
            hudNext = aheadType(aheadEv[0], aheadSide[0], aheadAng[0], aheadExit[0]);
            hudFresh = true;
        }
        for (int k = 0; k < 3; k++) aheadOk[k] = false;
    }

    // ---- remaining distance and arrival time

    void sendRemain() {
        if (!navLive || tripMeters < 0) return;
        if (tripGen == sentGen) {
            int usm = distanceUnit();
            if (usm == -1 || usm == lastUsm) return;
        }
        sentGen = tripGen;
        int m = tripMeters, u = tripUnit;
        int usm = distanceUnit();
        lastUsm = usm;
        if (usm == Car.DISTANCE_KM) u = m < 1000 ? METERS : KILOMETERS;
        else if (usm == Car.DISTANCE_MILES) u = m < 161 ? FEET : MILES;
        int whole, tenths = 0, cu;
        if (u == KILOMETERS || u == KILOMETERS_P1 || (u != MILES && u != MILES_P1 && u != FEET && u != YARDS && m >= 1000)) {
            whole = m / 1000;
            tenths = m % 1000 / 100;
            cu = Cluster.UNIT_KM;
        } else if (u == MILES || u == MILES_P1) {
            int t = m * 10 / 1609;
            tenths = t % 10;
            whole = t / 10;
            cu = Cluster.UNIT_MI;
        } else if (u == FEET) {
            whole = m * 3281 / 1000;
            cu = Cluster.UNIT_FT;
        } else if (u == YARDS) {
            whole = m * 1094 / 1000;
            cu = Cluster.UNIT_YD;
        } else {
            whole = m;
            cu = Cluster.UNIT_M;
        }
        int hour = -1, minute = -1, timeType = -1, timeFormat = -1;
        int s = tripSeconds;
        Calendar c = null;
        if (s >= 500000000) {
            c = Calendar.getInstance();
            c.setTimeInMillis(s * 1000L);
        } else if (s >= 0 && s <= 604800) {
            c = Calendar.getInstance();
            c.add(Calendar.SECOND, s);
        }
        if (c != null) {
            hour = c.get(Calendar.HOUR_OF_DAY);
            minute = c.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        }
        cluster.tbtSubInfo(whole + tenths / 10f, 0, cu, hour, minute, timeType, timeFormat, 0f, -1, estPoint);
        scheduleTick();
    }

    void scheduleTick() {
        if (tripMeters < 0) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, UNIT_TICK_MS);
    }

    void unitTick() {
        if (tripMeters < 0) return;
        sendRemain();
        scheduleTick();
    }
}
