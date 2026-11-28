package hmgapi.mods.wireless;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

public final class AaCompassTest {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    static class BaseSensorSource {
        final List<String> reports = new ArrayList<String>();
        volatile CountDownLatch latch;

        @SuppressWarnings("unused")
        private int nativeReportCompassData(int bearingE6, boolean hasPitch, int pitchE6, boolean hasRoll, int rollE6) {
            synchronized (reports) {
                reports.add(bearingE6 + ":" + hasPitch + ":" + pitchE6 + ":" + hasRoll + ":" + rollE6);
            }
            CountDownLatch l = latch;
            if (l != null) l.countDown();
            return 0;
        }

        List<String> snapshot() {
            synchronized (reports) {
                return new ArrayList<String>(reports);
            }
        }
    }

    static final class SubSensorSource extends BaseSensorSource {}

    public static void main(String[] args) throws Throwable {
        testPureConversionsAndSensors();
        testCoordinatorTimingAndStop();
        testEndToEndNaviToGpm();
        System.out.println("ok    AA car compass (AaCompass + NaviBridge): " + checks + " checks");
    }

    private static void testPureConversionsAndSensors() {
        // Gen5W cluster CompassDegree is counter-clockwise (0=N, 270=E, 180=S, 90=W)
        check(AaCompass.clusterToBearingDeg(0f) == 0f, "0 deg CCW -> 0 deg North");
        check(AaCompass.clusterToBearingDeg(-0f) == 0f, "-0 deg CCW -> +0 deg North");
        check(AaCompass.clusterToBearingDeg(360f) == 0f, "360 deg CCW -> 0 deg North");
        check(AaCompass.clusterToBearingDeg(270f) == 90f, "270 deg CCW -> 90 deg East");
        check(AaCompass.clusterToBearingDeg(180f) == 180f, "180 deg CCW -> 180 deg South");
        check(AaCompass.clusterToBearingDeg(90f) == 270f, "90 deg CCW -> 270 deg West");
        check(AaCompass.clusterToBearingDeg(172.5f) == 187.5f,
                "parked Ioniq 5 CompassDegree 172.5 CCW -> 187.5 deg SSW");
        check(AaCompass.clusterToBearingDeg(352.5f) == 7.5f, "352.5 deg CCW -> 7.5 deg NNE");
        check(AaCompass.clusterToBearingDeg(7.5f) == 352.5f, "7.5 deg CCW -> 352.5 deg NNW");
        check(Float.isNaN(AaCompass.clusterToBearingDeg(-1f)), "negative cluster degree rejected");
        check(Float.isNaN(AaCompass.clusterToBearingDeg(360.1f)), ">360 cluster degree rejected");
        check(Float.isNaN(AaCompass.clusterToBearingDeg(Float.NaN)), "NaN rejected");
        check(Float.isNaN(AaCompass.clusterToBearingDeg(Float.POSITIVE_INFINITY)), "Infinity rejected");

        // E6 scaling and wrap-around
        check(AaCompass.toBearingE6(0f) == 0, "0 deg -> 0 e6");
        check(AaCompass.toBearingE6(90f) == 90000000, "90 deg -> 90_000_000 e6");
        check(AaCompass.toBearingE6(187.5f) == 187500000, "187.5 deg -> 187_500_000 e6");
        check(AaCompass.toBearingE6(359.9999999f) == 0, "rounding to 360_000_000 wraps cleanly to 0");
        check(AaCompass.toBearingE6(360f) == 0, "360 deg -> 0 e6");
        check(AaCompass.toBearingE6(Float.NaN) == -1, "NaN -> -1");

        // Sub-info 9-arg and 10-arg extraction
        Object[] sub9 = { 0f, -3, 15, -1, -1, 3, 3, 172.5f, 0 };
        Object[] sub10 = { 0f, -3, 15, -1, -1, 3, 3, 270.0f, 0, 255 };
        Object[] subErr = { 10f, 0, 2, 8, 0, 1, 1, 0f, -1, 1 };
        check(AaCompass.extractSubInfoBearing(sub9) == 187.5f, "9-arg updateTBTSubInfo extracts 187.5 deg");
        check(AaCompass.extractSubInfoBearing(sub10) == 90.0f, "10-arg updateTBTSubInfo extracts 90.0 deg");
        check(Float.isNaN(AaCompass.extractSubInfoBearing(subErr)), "directionErr != 0 rejected");
        check(Float.isNaN(AaCompass.extractSubInfoBearing(new Object[] { 0f })), "short args rejected");

        // Fallback TBT info extraction (only DisplayType == 8 COMPASS)
        check(AaCompass.extractTbtInfoFallbackBearing(new Object[] { 8, 0, 180.0f }) == 180.0f,
                "DisplayType 8 fallback extracts 180.0 deg");
        check(Float.isNaN(AaCompass.extractTbtInfoFallbackBearing(new Object[] { 13, 0, 90.0f })),
                "non-compass DisplayType 13 turn direction never treated as car heading");

        // Sensor array augmentation
        int[] stock = { 1, 3, 10, 13 };
        int[] added = AaCompass.withSensor(stock, AaCompass.SENSOR_COMPASS);
        check(Arrays.equals(added, new int[] { 1, 3, 10, 13, 2 }), "SENSOR_COMPASS (2) appended once");
        check(AaCompass.withSensor(added, AaCompass.SENSOR_COMPASS) == added, "withSensor is idempotent");
        check(Arrays.equals(AaCompass.withSensor(null, AaCompass.SENSOR_COMPASS), new int[] { 2 }),
                "null sensor array handled safely");
    }

    private static void testCoordinatorTimingAndStop() throws Throwable {
        final List<Integer> sent = new ArrayList<Integer>();
        final CountDownLatch firstThree = new CountDownLatch(3);
        final CountDownLatch changedLatch = new CountDownLatch(1);
        final int[] targetChangedE6 = { -1 };

        AaCompass.Sink sink = new AaCompass.Sink() {
            @Override
            public int sendCompass(int bearingE6) {
                synchronized (sent) {
                    sent.add(Integer.valueOf(bearingE6));
                    if (bearingE6 == targetChangedE6[0]) changedLatch.countDown();
                }
                firstThree.countDown();
                return 0;
            }
        };

        ModContext mod = new ModContext();
        AaCompass.Coordinator coord = new AaCompass.Coordinator(20L, 45L);
        // Pre-cache parked heading before AA session starts
        coord.onCarBearing(187.5f);
        check(coord.cachedBearingE6() == 187500000, "coordinator caches bearing before session start");

        coord.startSession(mod, sink);
        check(firstThree.await(1000L, TimeUnit.MILLISECONDS),
                "periodic compass reports keep Google Maps fresh while parked");
        synchronized (sent) {
            check(sent.get(0).intValue() == 187500000, "first report matches cached car heading");
        }

        // Immediate wakeup on heading change without waiting for repeat timer
        targetChangedE6[0] = 90000000;
        long t0 = System.nanoTime();
        coord.onCarBearing(90f);
        check(changedLatch.await(500L, TimeUnit.MILLISECONDS), "heading change wakes worker immediately");
        long wakeMs = (System.nanoTime() - t0) / 1000000L;
        check(wakeMs < 250L, "heading change delivered promptly (" + wakeMs + " ms)");

        // Clean stop leaves no active worker and sends nothing further
        coord.stopSession();
        check(!coord.isSessionActive(), "stopSession terminates daemon worker cleanly");
        int countAfterStop;
        synchronized (sent) {
            countAfterStop = sent.size();
        }
        Thread.sleep(80L);
        synchronized (sent) {
            check(sent.size() == countAfterStop, "no compass sends occur after stopSession");
        }

        // Disabling mod stops active worker automatically
        AaCompass.Coordinator coord2 = new AaCompass.Coordinator(20L, 40L);
        coord2.onCarBearing(45f);
        final CountDownLatch started2 = new CountDownLatch(1);
        coord2.startSession(mod, new AaCompass.Sink() {
            @Override
            public int sendCompass(int bearingE6) {
                started2.countDown();
                return 0;
            }
        });
        check(started2.await(500L, TimeUnit.MILLISECONDS), "second session started");
        mod.modEnabled = false;
        Thread.sleep(90L);
        check(!coord2.isSessionActive(), "disabling mod exits worker automatically");
        coord2.stopSession();
    }

    private static void testEndToEndNaviToGpm() throws Throwable {
        Map<String, Context> bus = new LinkedHashMap<String, Context>();
        Context naviCtx = new Context(Wireless.NAVIGATION, bus);
        Context gpmCtx = new Context(Wireless.GPM, bus);

        ModContext naviMod = new ModContext();
        ModContext gpmMod = new ModContext();

        // 1. com.mnsoft.navi boots first while car is parked at CompassDegree = 172.5 CCW (187.5 SSW)
        NaviBridge.testNow = 1000L;
        NaviBridge.install(naviMod, new LoadedApp(Wireless.NAVIGATION, naviCtx), true,
                new String[] { NaviBridge.PT_TBT_INFO, NaviBridge.PT_SUB_INFO, NaviBridge.PT_SUB_INFO_B });
        MethodHook subHook = naviMod.hooks.get("framework.navigation_cluster.update_tbt_sub_info");
        subHook.before(new Object(), 0f, -3, 15, -1, -1, 3, 3, 172.5f, 0);

        // 2. HKMC_GPM loads later: installGpm sends REQUEST_COMPASS -> NaviBridge replies with CAR_COMPASS(187.5)
        AaCompass.Coordinator coord = AaCompass.installGpm(gpmMod, new LoadedApp(Wireless.GPM, gpmCtx));
        check(coord.cachedBearingE6() == 187500000,
                "startup REQUEST_COMPASS handshake populates parked heading in GPM immediately");

        MethodHook sensorInit = gpmMod.hooks.get("gpm.sensor_source.init");
        MethodHook destroy = gpmMod.hooks.get("gpm.receiver.destroy");
        MethodHook disconnected = gpmMod.hooks.get("gpm.receiver_service.disconnected");

        // 3. SensorSource.init beforeHookedMethod appends SENSOR_COMPASS (2); failed init (false) does not start worker
        SubSensorSource sensorSource = new SubSensorSource();
        int[] offered = { 1, 3, 10, 13 };
        MethodHook.Param initParam = sensorInit.before(sensorSource, 0, 99L, offered, 128, new int[0], new int[0]);
        check(Arrays.equals((int[]) initParam.args[2], new int[] { 1, 3, 10, 13, 2 }),
                "gpm.sensor_source.init advertises SENSOR_COMPASS (2)");
        sensorInit.afterResult(sensorSource, Boolean.FALSE, initParam.args);
        check(!coord.isSessionActive() && sensorSource.snapshot().isEmpty(),
                "failed SensorSource.init does not start compass sender");

        // 4. Successful SensorSource.init starts sender and immediately invokes nativeReportCompassData
        sensorSource.latch = new CountDownLatch(1);
        sensorInit.afterResult(sensorSource, Boolean.TRUE, initParam.args);
        check(sensorSource.latch.await(1000L, TimeUnit.MILLISECONDS),
                "successful SensorSource.init immediately reports parked car compass");
        check(sensorSource.snapshot().get(0).equals("187500000:false:0:false:0"),
                "nativeReportCompassData called with exact (bearingE6, false, 0, false, 0) signature");

        // 5. While AA guidance suppresses stock cluster TBT, car turns East (270.0 CCW -> 90.0 E) -> pushed to AA!
        NaviBridge.testNow = 2000L;
        Intent phoneActive = new Intent(NaviBridge.ACTION_PHONE_TBT)
                .setPackage(Wireless.NAVIGATION)
                .putExtra(NaviBridge.EXTRA_SOURCE, NaviBridge.SOURCE_AA)
                .putExtra(NaviBridge.EXTRA_ACTIVE, true)
                .putExtra(NaviBridge.EXTRA_BAR, 60);
        gpmCtx.sendBroadcast(phoneActive);

        sensorSource.latch = new CountDownLatch(1);
        NaviBridge.testNow = 2500L;
        MethodHook.Param supParam = subHook.before(new Object(), 0f, -3, 15, -1, -1, 3, 3, 270.0f, 0);
        check(supParam.isReturnEarly(), "stock TBT write suppressed while AA guides");
        check(sensorSource.latch.await(1000L, TimeUnit.MILLISECONDS),
                "live compass update still reaches Android Auto during suppressed stock TBT");
        List<String> snap = sensorSource.snapshot();
        check(snap.get(snap.size() - 1).equals("90000000:false:0:false:0"),
                "updated East heading (90_000_000 e6) reported to SensorSource");

        // 6. Wireless disconnect (onDisconnected without destroy) stops session cleanly; destroy is also safe
        disconnected.before(new Object(), 1, false, 0);
        check(!coord.isSessionActive(), "gpm.receiver_service.disconnected stops compass sender");
        destroy.before(new Object());
        check(!coord.isSessionActive(), "subsequent gpm.receiver.destroy is idempotent");
        check(gpmMod.errors.isEmpty() && naviMod.errors.isEmpty(), "zero mod errors logged");

        NaviBridge.testNow = -1;
    }
}
