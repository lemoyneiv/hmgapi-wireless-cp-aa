package hmgapi.mods.wireless;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host test of AaNavState. The fixtures are built here in the shapes the US Ioniq 5's
 * 2026-11-03 drive captures had (one step, road text in fields 2 and 4, 2-3 lanes, address-only
 * destinations; 0x8007 with display text, tenths of a mile or feet, time to step, ETA), plus
 * the charging-stop destination EGMP verified on 2026-10-29. No captured text is in here.
 *
 * Arguments: logcat files with NAVDUMP lines (optional). Every S/P line must parse; only
 * counts are printed, never strings.
 */
public class AaNavStateTest {
    static int fails;

    static void check(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": want " + want + ", got " + got));
        if (!ok) fails++;
    }

    // ---- a tiny protobuf writer
    static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        W v(int f, long x) {
            raw((f << 3));
            raw(x);
            return this;
        }

        W s(int f, String x) {
            try {
                return b(f, x.getBytes("UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        W m(int f, W x) {
            return b(f, x.o.toByteArray());
        }

        W b(int f, byte[] x) {
            raw((f << 3) | 2);
            raw(x.length);
            o.write(x, 0, x.length);
            return this;
        }

        void raw(long x) {
            while ((x & ~0x7FL) != 0) {
                o.write((int) ((x & 0x7F) | 0x80));
                x >>>= 7;
            }
            o.write((int) x);
        }

        String line(char kind) {
            byte[] b = o.toByteArray();
            StringBuilder sb = new StringBuilder();
            sb.append(kind).append(' ').append(b.length).append(' ');
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        }
    }

    static W w() {
        return new W();
    }

    static W lane(int... shapeHl) {
        W l = w();
        for (int i = 0; i < shapeHl.length; i += 2) {
            W d = w().v(1, shapeHl[i]);
            if (shapeHl[i + 1] != 0) d.v(2, 1);
            l.m(1, d);
        }
        return l;
    }

    public static void main(String[] args) throws Exception {
        // 0x8006: right turn, road twice, three lanes (left only / straight / right recommended), one destination
        String s1 = w().m(1, w()
                        .m(1, w().v(1, AaNavState.M_TURN_NORMAL_RIGHT))
                        .m(2, w().s(1, "toward Test Rd"))
                        .m(3, lane(AaNavState.L_NORMAL_LEFT, 0))
                        .m(3, lane(AaNavState.L_STRAIGHT, 0))
                        .m(3, lane(AaNavState.L_STRAIGHT, 0, AaNavState.L_NORMAL_RIGHT, 1))
                        .m(4, w().s(1, "toward Test Rd")))
                .m(2, w().s(1, "1 Example St"))
                .line('S');
        AaNavState.Dump d = AaNavState.parse(s1);
        AaNavState.Step st = d.state.step();
        check("S kind", 'S', d.kind);
        check("S one step", 1, d.state.steps.size());
        check("S type", AaNavState.M_TURN_NORMAL_RIGHT, st.type);
        check("S no roundabout exit", -1, st.exitNumber);
        check("S road (field 2)", "toward Test Rd", st.road);
        check("S cue (field 4)", "toward Test Rd", st.cue.get(0));
        check("S lanes", 3, st.lanes.size());
        check("S lane 3 shapes", 2, st.lanes.get(2).shapes.length);
        check("S lane 3 right recommended", true, st.lanes.get(2).highlighted[1]);
        check("S lane 1 not recommended", false, st.lanes.get(0).recommended());
        check("S destination address", "1 Example St", d.state.destinations.get(0).address);
        check("S no station", false, d.state.destinations.get(0).station);
        check("S side", AaNavState.SIDE_RIGHT, st.side());
        check("S legacy event", AaTurns.TURN, AaNavState.legacy(st.type)[0]);
        check("S legacy side", AaTurns.RIGHT, AaNavState.legacy(st.type)[1]);
        check("S summary has no text", "S steps=1 t=8 x=-1 a=-1 lanes=3(1) cue=1 dest=1(st 0)", AaNavState.summary(d));

        // 0x8006: roundabout with exit number and angle; two stops, the first a 350 kW charger
        String s2 = w().m(1, w()
                        .m(1, w().v(1, AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE).v(2, 3).v(3, 270))
                        .m(2, w().s(1, "Ring")))
                .m(2, w().s(1, "Charger").m(2, w().v(1, 18000).v(2, 350000).v(3, 1432)))
                .m(2, w().s(1, "Home").m(2, w().v(1, 9000).v(2, 0).v(3, -1L)))
                .line('S');
        d = AaNavState.parse(s2);
        st = d.state.step();
        check("roundabout type", AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, st.type);
        check("roundabout exit", 3, st.exitNumber);
        check("roundabout angle", 270, st.exitAngle);
        check("roundabout is roundabout", true, AaNavState.isRoundabout(st.type));
        check("roundabout legacy", AaTurns.ROUNDABOUT_ENTER_AND_EXIT, AaNavState.legacy(st.type)[0]);
        AaNavState.Destination c = d.state.destinations.get(0);
        check("charger station", true, c.station);
        check("charger departure Wh", 18000, c.departureWh);
        check("charger power W", 350000, c.maxPowerW);
        check("charger planned s", 1432, c.chargeSeconds);
        check("home int64 -1 = none", -1, d.state.destinations.get(1).chargeSeconds);

        // 0x8007: 0.4 mi (MILES_P1) to the step, 25 s; destination 1.3 mi, "4:22 PM", 139 s
        String p1 = w().m(1, w().m(1, w().v(1, 610).s(2, "0.4").v(3, AaTurns.MILES_P1)).v(2, 25))
                .m(2, w().m(1, w().v(1, 2093).s(2, "1.3").v(3, AaTurns.MILES_P1)).s(2, "4:22 PM").v(3, 139))
                .line('P');
        d = AaNavState.parse(p1);
        check("P kind", 'P', d.kind);
        check("P meters", 610, d.position.step.meters);
        check("P display", "0.4", d.position.step.display);
        check("P unit", AaTurns.MILES_P1, d.position.step.unit);
        check("P time to step", 25L, d.position.timeToStep);
        check("P dest meters", 2093, d.position.destinations.get(0).distance.meters);
        check("P eta", "4:22 PM", d.position.destinations.get(0).eta);
        check("P tta", 139L, d.position.destinations.get(0).timeToArrival);
        check("P no current road", null, d.position.currentRoad);
        check("P summary", "P m=610 u=5 tts=25 dest=1 dm=2093 tta=139 road=0", AaNavState.summary(d));

        // 0x8007 with a current road, no time to step
        d = AaNavState.parse(w().m(1, w().m(1, w().v(1, 30).s(2, "100").v(3, AaTurns.FEET))).m(3, w().s(1, "Main St")).line('P'));
        check("P2 time to step absent", -1L, d.position.timeToStep);
        check("P2 current road", "Main St", d.position.currentRoad);

        // unknown fields are skipped
        d = AaNavState.parse(w().m(1, w().m(1, w().v(1, 36)).v(9, 5).m(10, w().v(1, 1))).v(7, 1).line('S'));
        check("unknown fields skipped", AaNavState.M_STRAIGHT, d.state.step().type);

        // other kinds and bad lines
        check("other kind", null, AaNavState.parse("X 1 00"));
        check("null", null, AaNavState.parse(null));
        threw("length mismatch", "S 5 0a00");
        threw("odd hex", "S 1 0a0");
        threw("truncated", "S 2 0a05");

        // every maneuver has a legacy event; all sided types have a side
        int unknown = 0;
        for (int t = 1; t <= 50; t++) if (AaNavState.legacy(t)[0] == AaTurns.UNKNOWN) unknown++;
        check("every maneuver 1..50 maps to a legacy event", 0, unknown);
        check("keep left is left", AaNavState.SIDE_LEFT, AaNavState.side(AaNavState.M_KEEP_LEFT));
        check("off ramp right is a ramp", true, AaNavState.isRamp(AaNavState.M_OFF_RAMP_NORMAL_RIGHT));
        check("off ramp legacy", AaTurns.OFF_RAMP, AaNavState.legacy(AaNavState.M_OFF_RAMP_SLIGHT_LEFT)[0]);
        check("on ramp legacy", AaTurns.ON_RAMP, AaNavState.legacy(AaNavState.M_ON_RAMP_U_TURN_RIGHT)[0]);
        check("destination right", AaNavState.SIDE_RIGHT, AaNavState.side(AaNavState.M_DESTINATION_RIGHT));

        for (String f : args) captures(f);

        System.out.println(fails == 0 ? "all passed" : fails + " FAILED");
        if (fails != 0) System.exit(1);
    }

    static void threw(String what, String line) {
        try {
            AaNavState.parse(line);
            check(what + " throws", true, false);
        } catch (AaNavState.ParseException e) {
            check(what + " throws", true, true);
        }
    }

    /** Parses every NAVDUMP S/P line of a capture; prints counts only. */
    static void captures(String file) throws Exception {
        Pattern p = Pattern.compile("NAVDUMP.*?: ([SP] \\d+ [0-9a-f]+)");
        Map<String, Integer> n = new TreeMap<String, Integer>();
        int bad = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        for (String line; (line = r.readLine()) != null; ) {
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            try {
                AaNavState.Dump d = AaNavState.parse(m.group(1));
                String k = AaNavState.summary(d).replaceAll("(m|dm|tts|tta)=\\d+", "$1=#");
                n.put(k, n.containsKey(k) ? n.get(k) + 1 : 1);
            } catch (AaNavState.ParseException e) {
                bad++;
            }
        }
        r.close();
        int total = 0;
        for (int v : n.values()) total += v;
        System.out.println("      capture " + file.replaceAll(".*/(.*/.*)$", "$1") + ": " + total + " parsed");
        for (Map.Entry<String, Integer> e : n.entrySet()) System.out.println("        " + e.getValue() + "  " + e.getKey());
        check("capture parses with no errors", 0, bad);
    }
}
