package hmgapi.mods.wireless;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for the full Android Auto navigation messages the AA library hands over as the
 * event-33 dump ("NAVDUMP"): "S <len> <hex>" is 0x8006 (navigation state: the step and the
 * destinations), "P <len> <hex>" is 0x8007 (current position: distances and times).
 *
 * The library's own parser folds 0x8006 into the legacy next-turn event (TURN + side and so
 * on), which drops the exact maneuver (keep, slight/sharp, ramp side, fork, merge, roundabout
 * direction, destination side). This reads the raw message instead, so nothing is lost.
 *
 * Layout (aa-proxy-rs protos.proto and open-android-auto NavigationNotificationMessage.proto,
 * which agree on numbers; checked against the US Ioniq 5's 2026-11-03 drive captures):
 * <pre>
 * 0x8006 { 1 step* { 1 maneuver { 1 type (0..50), 2 roundabout exit number, 3 exit angle },
 *                    2 { 1 text },            (road / instruction)
 *                    3 lane* { 1 direction* { 1 shape (0..9), 2 highlighted } },
 *                    4 { 1 text* } },         (cue / road names)
 *          2 destination* { 1 address, 2 charging stop { 1 departure Wh, 2 max power W,
 *                                                       3 planned charging s (int64, -1 none) } } }
 * 0x8007 { 1 { 1 distance { 1 meters, 2 display, 3 unit }, 2 time to step s },
 *          2 destination* { 1 distance, 2 ETA text, 3 time to arrival s },
 *          3 current road { 1 name } }
 * </pre>
 * In the captures Maps sent one step per 0x8006, fields 2 and 4 carried the same text, and
 * 0x8007 never had the current road. Every field is optional here.
 *
 * Pure Java (no Android), so it runs in the host test.
 */
final class AaNavState {
    // NavigationManeuver.type (aa-proxy-rs NavigationType; 43..50 from open-android-auto)
    static final int M_UNKNOWN = 0, M_DEPART = 1, M_NAME_CHANGE = 2, M_KEEP_LEFT = 3, M_KEEP_RIGHT = 4,
            M_TURN_SLIGHT_LEFT = 5, M_TURN_SLIGHT_RIGHT = 6, M_TURN_NORMAL_LEFT = 7, M_TURN_NORMAL_RIGHT = 8,
            M_TURN_SHARP_LEFT = 9, M_TURN_SHARP_RIGHT = 10, M_U_TURN_LEFT = 11, M_U_TURN_RIGHT = 12,
            M_ON_RAMP_SLIGHT_LEFT = 13, M_ON_RAMP_SLIGHT_RIGHT = 14, M_ON_RAMP_NORMAL_LEFT = 15,
            M_ON_RAMP_NORMAL_RIGHT = 16, M_ON_RAMP_SHARP_LEFT = 17, M_ON_RAMP_SHARP_RIGHT = 18,
            M_ON_RAMP_U_TURN_LEFT = 19, M_ON_RAMP_U_TURN_RIGHT = 20, M_OFF_RAMP_SLIGHT_LEFT = 21,
            M_OFF_RAMP_SLIGHT_RIGHT = 22, M_OFF_RAMP_NORMAL_LEFT = 23, M_OFF_RAMP_NORMAL_RIGHT = 24,
            M_FORK_LEFT = 25, M_FORK_RIGHT = 26, M_MERGE_LEFT = 27, M_MERGE_RIGHT = 28, M_MERGE_SIDE_UNSPECIFIED = 29,
            M_ROUNDABOUT_ENTER = 30, M_ROUNDABOUT_EXIT = 31, M_ROUNDABOUT_ENTER_AND_EXIT_CW = 32,
            M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE = 33, M_ROUNDABOUT_ENTER_AND_EXIT_CCW = 34,
            M_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE = 35, M_STRAIGHT = 36, M_FERRY_BOAT = 37, M_FERRY_TRAIN = 38,
            M_DESTINATION = 39, M_DESTINATION_STRAIGHT = 40, M_DESTINATION_LEFT = 41, M_DESTINATION_RIGHT = 42,
            M_ROUNDABOUT_ENTER_CW = 43, M_ROUNDABOUT_ENTER_CCW = 44, M_ROUNDABOUT_EXIT_CW = 45,
            M_ROUNDABOUT_EXIT_CCW = 46, M_FERRY_BOAT_LEFT = 47, M_FERRY_BOAT_RIGHT = 48, M_FERRY_TRAIN_LEFT = 49,
            M_FERRY_TRAIN_RIGHT = 50;
    // LaneDirection.shape
    static final int L_UNKNOWN = 0, L_STRAIGHT = 1, L_SLIGHT_LEFT = 2, L_SLIGHT_RIGHT = 3, L_NORMAL_LEFT = 4,
            L_NORMAL_RIGHT = 5, L_SHARP_LEFT = 6, L_SHARP_RIGHT = 7, L_U_TURN_LEFT = 8, L_U_TURN_RIGHT = 9;
    /** maneuver side */
    static final int SIDE_NONE = 0, SIDE_LEFT = 1, SIDE_RIGHT = 2;

    static final class ParseException extends Exception {
        ParseException(String msg) {
            super(msg);
        }
    }

    static final class Lane {
        /** shapes, in the order sent; highlighted[i] = Maps recommends shapes[i] */
        final int[] shapes;
        final boolean[] highlighted;

        Lane(int[] shapes, boolean[] highlighted) {
            this.shapes = shapes;
            this.highlighted = highlighted;
        }

        boolean recommended() {
            for (boolean h : highlighted) if (h) return true;
            return false;
        }
    }

    static final class Step {
        int type = -1, exitNumber = -1, exitAngle = -1;
        /** field 2 text (road or instruction); null if absent */
        String road;
        /** field 4 texts (cue / road names) */
        final List<String> cue = new ArrayList<String>();
        final List<Lane> lanes = new ArrayList<Lane>();

        int side() {
            return AaNavState.side(type);
        }
    }

    static final class Destination {
        /** address; null if absent. Keep out of logs. */
        String address;
        boolean station;
        int departureWh = -1, maxPowerW = -1, chargeSeconds = -1;
    }

    /** 0x8006 */
    static final class State {
        final List<Step> steps = new ArrayList<Step>();
        final List<Destination> destinations = new ArrayList<Destination>();

        Step step() {
            return steps.isEmpty() ? null : steps.get(0);
        }
    }

    static final class Distance {
        int meters = -1, unit = -1;
        /** as shown on the phone, e.g. "0.4"; null if absent */
        String display;
    }

    static final class DestinationDistance {
        final Distance distance = new Distance();
        String eta;
        long timeToArrival = -1;
    }

    /** 0x8007 */
    static final class Position {
        final Distance step = new Distance();
        long timeToStep = -1;
        final List<DestinationDistance> destinations = new ArrayList<DestinationDistance>();
        String currentRoad;
    }

    /** One parsed dump line: state for 'S', position for 'P'. */
    static final class Dump {
        final char kind;
        final State state;
        final Position position;

        Dump(char kind, State state, Position position) {
            this.kind = kind;
            this.state = state;
            this.position = position;
        }
    }

    private AaNavState() {
    }

    /** Parses "S|P <len> <hex>"; null for other dump kinds. Throws on a bad line. */
    static Dump parse(String line) throws ParseException {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() < 3 || s.charAt(1) != ' ') return null;
        char kind = s.charAt(0);
        if (kind != 'S' && kind != 'P') return null;
        int sp = s.indexOf(' ', 2);
        if (sp < 0) throw new ParseException("no payload");
        int declared;
        try {
            declared = Integer.parseInt(s.substring(2, sp));
        } catch (NumberFormatException e) {
            throw new ParseException("bad length");
        }
        byte[] b = hex(s.substring(sp + 1).trim());
        if (b.length != declared) throw new ParseException("length " + b.length + " != " + declared);
        return kind == 'S' ? new Dump('S', state(b), null) : new Dump('P', null, position(b));
    }

    static State state(byte[] b) throws ParseException {
        State st = new State();
        Reader r = new Reader(b, 0, b.length);
        while (r.more()) {
            int tag = r.tag();
            if (tag == lenTag(1)) st.steps.add(step(r.sub()));
            else if (tag == lenTag(2)) st.destinations.add(destination(r.sub()));
            else r.skip(tag);
        }
        return st;
    }

    private static Step step(Reader r) throws ParseException {
        Step s = new Step();
        while (r.more()) {
            int tag = r.tag();
            if (tag == lenTag(1)) {
                Reader m = r.sub();
                while (m.more()) {
                    int t = m.tag();
                    if (t == intTag(1)) s.type = (int) m.varint();
                    else if (t == intTag(2)) s.exitNumber = (int) m.varint();
                    else if (t == intTag(3)) s.exitAngle = (int) m.varint();
                    else m.skip(t);
                }
            } else if (tag == lenTag(2)) {
                Reader t2 = r.sub();
                while (t2.more()) {
                    int t = t2.tag();
                    if (t == lenTag(1)) s.road = t2.string();
                    else t2.skip(t);
                }
            } else if (tag == lenTag(3)) {
                s.lanes.add(lane(r.sub()));
            } else if (tag == lenTag(4)) {
                Reader c = r.sub();
                while (c.more()) {
                    int t = c.tag();
                    if (t == lenTag(1)) s.cue.add(c.string());
                    else c.skip(t);
                }
            } else {
                r.skip(tag);
            }
        }
        return s;
    }

    private static Lane lane(Reader r) throws ParseException {
        List<int[]> dirs = new ArrayList<int[]>();
        while (r.more()) {
            int tag = r.tag();
            if (tag == lenTag(1)) {
                Reader d = r.sub();
                int shape = L_UNKNOWN, hl = 0;
                while (d.more()) {
                    int t = d.tag();
                    if (t == intTag(1)) shape = (int) d.varint();
                    else if (t == intTag(2)) hl = d.varint() != 0 ? 1 : 0;
                    else d.skip(t);
                }
                dirs.add(new int[]{shape, hl});
            } else {
                r.skip(tag);
            }
        }
        int[] shapes = new int[dirs.size()];
        boolean[] hl = new boolean[dirs.size()];
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = dirs.get(i)[0];
            hl[i] = dirs.get(i)[1] != 0;
        }
        return new Lane(shapes, hl);
    }

    private static Destination destination(Reader r) throws ParseException {
        Destination d = new Destination();
        while (r.more()) {
            int tag = r.tag();
            if (tag == lenTag(1)) {
                d.address = r.string();
            } else if (tag == lenTag(2)) {
                d.station = true;
                Reader c = r.sub();
                while (c.more()) {
                    int t = c.tag();
                    if (t == intTag(1)) d.departureWh = clampInt(c.varint());
                    else if (t == intTag(2)) d.maxPowerW = clampInt(c.varint());
                    else if (t == intTag(3)) d.chargeSeconds = clampInt(c.varint());   // int64 -1: none planned
                    else c.skip(t);
                }
            } else {
                r.skip(tag);
            }
        }
        return d;
    }

    static Position position(byte[] b) throws ParseException {
        Position p = new Position();
        Reader r = new Reader(b, 0, b.length);
        while (r.more()) {
            int tag = r.tag();
            if (tag == lenTag(1)) {
                Reader s = r.sub();
                while (s.more()) {
                    int t = s.tag();
                    if (t == lenTag(1)) distance(s.sub(), p.step);
                    else if (t == intTag(2)) p.timeToStep = s.varint();
                    else s.skip(t);
                }
            } else if (tag == lenTag(2)) {
                Reader s = r.sub();
                DestinationDistance dd = new DestinationDistance();
                while (s.more()) {
                    int t = s.tag();
                    if (t == lenTag(1)) distance(s.sub(), dd.distance);
                    else if (t == lenTag(2)) dd.eta = s.string();
                    else if (t == intTag(3)) dd.timeToArrival = s.varint();
                    else s.skip(t);
                }
                p.destinations.add(dd);
            } else if (tag == lenTag(3)) {
                Reader s = r.sub();
                while (s.more()) {
                    int t = s.tag();
                    if (t == lenTag(1)) p.currentRoad = s.string();
                    else s.skip(t);
                }
            } else {
                r.skip(tag);
            }
        }
        return p;
    }

    private static void distance(Reader r, Distance d) throws ParseException {
        while (r.more()) {
            int t = r.tag();
            if (t == intTag(1)) d.meters = clampInt(r.varint());
            else if (t == lenTag(2)) d.display = r.string();
            else if (t == intTag(3)) d.unit = (int) r.varint();
            else r.skip(t);
        }
    }

    // ---- maneuver helpers

    /** The maneuver's side: SIDE_LEFT, SIDE_RIGHT or SIDE_NONE. Roundabouts: CW = left-hand traffic. */
    static int side(int type) {
        switch (type) {
            case M_KEEP_LEFT: case M_TURN_SLIGHT_LEFT: case M_TURN_NORMAL_LEFT: case M_TURN_SHARP_LEFT:
            case M_U_TURN_LEFT: case M_ON_RAMP_SLIGHT_LEFT: case M_ON_RAMP_NORMAL_LEFT: case M_ON_RAMP_SHARP_LEFT:
            case M_ON_RAMP_U_TURN_LEFT: case M_OFF_RAMP_SLIGHT_LEFT: case M_OFF_RAMP_NORMAL_LEFT: case M_FORK_LEFT:
            case M_MERGE_LEFT: case M_DESTINATION_LEFT: case M_FERRY_BOAT_LEFT: case M_FERRY_TRAIN_LEFT:
                return SIDE_LEFT;
            case M_KEEP_RIGHT: case M_TURN_SLIGHT_RIGHT: case M_TURN_NORMAL_RIGHT: case M_TURN_SHARP_RIGHT:
            case M_U_TURN_RIGHT: case M_ON_RAMP_SLIGHT_RIGHT: case M_ON_RAMP_NORMAL_RIGHT: case M_ON_RAMP_SHARP_RIGHT:
            case M_ON_RAMP_U_TURN_RIGHT: case M_OFF_RAMP_SLIGHT_RIGHT: case M_OFF_RAMP_NORMAL_RIGHT: case M_FORK_RIGHT:
            case M_MERGE_RIGHT: case M_DESTINATION_RIGHT: case M_FERRY_BOAT_RIGHT: case M_FERRY_TRAIN_RIGHT:
                return SIDE_RIGHT;
            default:
                return SIDE_NONE;
        }
    }

    static boolean isRoundabout(int type) {
        return (type >= M_ROUNDABOUT_ENTER && type <= M_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE)
                || (type >= M_ROUNDABOUT_ENTER_CW && type <= M_ROUNDABOUT_EXIT_CCW);
    }

    static boolean isRamp(int type) {
        return type >= M_ON_RAMP_SLIGHT_LEFT && type <= M_OFF_RAMP_NORMAL_RIGHT;
    }

    static boolean isDestination(int type) {
        return type >= M_DESTINATION && type <= M_DESTINATION_RIGHT;
    }

    /**
     * The legacy next-turn event (AaTurns.DEPART.. DESTINATION) and side this maneuver maps to,
     * as {event, side}; for comparing with what the library's parser sent.
     */
    static int[] legacy(int type) {
        int s = side(type);
        int side = s == SIDE_LEFT ? AaTurns.LEFT : s == SIDE_RIGHT ? AaTurns.RIGHT : AaTurns.UNSPECIFIED;
        int ev;
        switch (type) {
            case M_DEPART: ev = AaTurns.DEPART; break;
            case M_NAME_CHANGE: ev = AaTurns.NAME_CHANGE; break;
            case M_KEEP_LEFT: case M_KEEP_RIGHT: case M_TURN_SLIGHT_LEFT: case M_TURN_SLIGHT_RIGHT:
                ev = AaTurns.SLIGHT_TURN; break;
            case M_TURN_NORMAL_LEFT: case M_TURN_NORMAL_RIGHT: ev = AaTurns.TURN; break;
            case M_TURN_SHARP_LEFT: case M_TURN_SHARP_RIGHT: ev = AaTurns.SHARP_TURN; break;
            case M_U_TURN_LEFT: case M_U_TURN_RIGHT: ev = AaTurns.U_TURN; break;
            case M_FORK_LEFT: case M_FORK_RIGHT: ev = AaTurns.FORK; break;
            case M_MERGE_LEFT: case M_MERGE_RIGHT: case M_MERGE_SIDE_UNSPECIFIED: ev = AaTurns.MERGE; break;
            case M_ROUNDABOUT_ENTER: case M_ROUNDABOUT_ENTER_CW: case M_ROUNDABOUT_ENTER_CCW:
                ev = AaTurns.ROUNDABOUT_ENTER; break;
            case M_ROUNDABOUT_EXIT: case M_ROUNDABOUT_EXIT_CW: case M_ROUNDABOUT_EXIT_CCW:
                ev = AaTurns.ROUNDABOUT_EXIT; break;
            case M_ROUNDABOUT_ENTER_AND_EXIT_CW: case M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE:
            case M_ROUNDABOUT_ENTER_AND_EXIT_CCW: case M_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE:
                ev = AaTurns.ROUNDABOUT_ENTER_AND_EXIT; break;
            case M_STRAIGHT: ev = AaTurns.STRAIGHT; break;
            case M_FERRY_BOAT: case M_FERRY_BOAT_LEFT: case M_FERRY_BOAT_RIGHT: ev = AaTurns.FERRY_BOAT; break;
            case M_FERRY_TRAIN: case M_FERRY_TRAIN_LEFT: case M_FERRY_TRAIN_RIGHT: ev = AaTurns.FERRY_TRAIN; break;
            case M_DESTINATION: case M_DESTINATION_STRAIGHT: case M_DESTINATION_LEFT: case M_DESTINATION_RIGHT:
                ev = AaTurns.DESTINATION; break;
            default:
                ev = isRamp(type) ? (type <= M_ON_RAMP_U_TURN_RIGHT ? AaTurns.ON_RAMP : AaTurns.OFF_RAMP)
                        : AaTurns.UNKNOWN;
        }
        // Roundabouts keep UNSPECIFIED: the direction is in the type (CW/CCW), and how the
        // library turns that into a side is still being settled (fix/aa-roundabout-direction).
        return new int[]{ev, side};
    }

    /** A short log line with no addresses or road names: "S t=8 x=-1 a=-1 lanes=3(1) dest=1(st 0)". */
    static String summary(Dump d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.kind);
        if (d.state != null) {
            Step s = d.state.step();
            sb.append(" steps=").append(d.state.steps.size());
            if (s != null) {
                sb.append(" t=").append(s.type).append(" x=").append(s.exitNumber).append(" a=").append(s.exitAngle);
                int rec = 0;
                for (Lane l : s.lanes) if (l.recommended()) rec++;
                sb.append(" lanes=").append(s.lanes.size()).append('(').append(rec).append(')');
                sb.append(" cue=").append(s.cue.size());
            }
            int st = 0;
            for (Destination dd : d.state.destinations) if (dd.station) st++;
            sb.append(" dest=").append(d.state.destinations.size()).append("(st ").append(st).append(')');
        } else if (d.position != null) {
            Position p = d.position;
            sb.append(" m=").append(p.step.meters).append(" u=").append(p.step.unit)
                    .append(" tts=").append(p.timeToStep).append(" dest=").append(p.destinations.size());
            if (!p.destinations.isEmpty()) {
                DestinationDistance dd = p.destinations.get(0);
                sb.append(" dm=").append(dd.distance.meters).append(" tta=").append(dd.timeToArrival);
            }
            sb.append(" road=").append(p.currentRoad != null ? 1 : 0);
        }
        return sb.toString();
    }

    // ---- wire

    private static int lenTag(int f) {
        return (f << 3) | 2;
    }

    private static int intTag(int f) {
        return f << 3;
    }

    private static int clampInt(long v) {
        return v >= 0 && v <= Integer.MAX_VALUE ? (int) v : -1;
    }

    static byte[] hex(String h) throws ParseException {
        int n = h.length();
        if ((n & 1) != 0) throw new ParseException("odd hex length");
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new ParseException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Bounds-checked protobuf wire reader over b[pos, end) (after EGMP's NavProto). */
    static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int tag() throws ParseException {
            long t = varint();
            if (t <= 0 || t > Integer.MAX_VALUE || (t >>> 3) == 0) throw new ParseException("bad tag");
            return (int) t;
        }

        long varint() throws ParseException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new ParseException("truncated varint");
                int x = b[pos++] & 0xff;
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new ParseException("varint too long");
        }

        private int length() throws ParseException {
            long n = varint();
            if (n < 0 || n > end - pos) throw new ParseException("truncated field");
            return (int) n;
        }

        Reader sub() throws ParseException {
            int n = length();
            Reader r = new Reader(b, pos, pos + n);
            pos += n;
            return r;
        }

        String string() throws ParseException {
            int n = length();
            String s;
            try {
                s = new String(b, pos, n, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                s = "";
            }
            pos += n;
            return s;
        }

        void skip(int tag) throws ParseException {
            switch (tag & 7) {
                case 0: varint(); return;
                case 1: advance(8); return;
                case 2: advance(length()); return;
                case 5: advance(4); return;
                default: throw new ParseException("wire type " + (tag & 7));
            }
        }

        private void advance(int n) throws ParseException {
            if (n > end - pos) throw new ParseException("truncated field");
            pos += n;
        }
    }
}
