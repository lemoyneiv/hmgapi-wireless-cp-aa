package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

/**
 * Feeds the car navigation's dead-reckoned dash compass heading into Android Auto
 * ({@code SENSOR_COMPASS = 2}, {@code SensorSource.nativeReportCompassData}) so Google Maps
 * knows which direction the car is facing while parked or pulling out, instead of spinning
 * on noisy stationary GPS bearings.
 *
 * Stock {@code libautoreceiver_infobank.so} already accepts sensor types 1..21, so this
 * requires no native library patch and works on every mapped Gen5W build.
 *
 * Coordinate convention: Gen5W's {@code NavigationCluster} ({@code updateTBTInfo} type 8 and
 * {@code updateTBTSubInfo} {@code CompassDegree}) encodes the counter-clockwise compass-rose
 * rotation angle ({@code 0 = N, 90 = W, 180 = S, 270 = E}), whereas Android Auto's
 * {@code CompassData.bearing_e6} expects clockwise degrees from true North * 1,000,000
 * ({@code [0, 359_999_999]}). {@link #clusterToBearingDeg(float)} converts between them.
 */
final class AaCompass {
    static final int SENSOR_COMPASS = 2;
    static final long WARMUP_MS = 250L;
    static final long REPEAT_MS = 1000L;

    static final String ACTION_CAR_COMPASS = "hmgapi.mods.wireless.CAR_COMPASS";
    static final String ACTION_REQUEST_COMPASS = "hmgapi.mods.wireless.REQUEST_COMPASS";
    static final String EXTRA_BEARING_DEG = "bearing_deg";

    private AaCompass() {}

    interface Sink {
        int sendCompass(int bearingE6) throws Exception;
    }

    /**
     * Converts Gen5W's counter-clockwise cluster {@code CompassDegree} ({@code 0..360}) into
     * clockwise degrees from true North ({@code [0, 360)}), or {@code Float.NaN} if invalid.
     */
    static float clusterToBearingDeg(float clusterDeg) {
        if (Float.isNaN(clusterDeg) || Float.isInfinite(clusterDeg) || clusterDeg < 0f || clusterDeg > 360f) {
            return Float.NaN;
        }
        double cw = (360.0d - (clusterDeg % 360.0d)) % 360.0d;
        if (cw <= 0.0d || cw >= 360.0d) return 0f;
        float out = (float) cw;
        return out >= 360f ? 0f : out;
    }

    /**
     * Extracts clockwise true bearing from {@code NavigationCluster.updateTBTSubInfo} args
     * (9- or 10-parameter overload): {@code args[7]} is {@code CompassDegree} (7.5 deg steps)
     * when {@code args[8]} ({@code directionErr}) is {@code 0}.
     */
    static float extractSubInfoBearing(Object[] args) {
        if (args == null || args.length < 9) return Float.NaN;
        if (!(args[8] instanceof Integer) || ((Integer) args[8]).intValue() != 0) return Float.NaN;
        if (!(args[7] instanceof Number)) return Float.NaN;
        return clusterToBearingDeg(((Number) args[7]).floatValue());
    }

    /**
     * Bootstrap fallback from {@code NavigationCluster.updateTBTInfo} when {@code DisplayType == 8}
     * ({@code COMPASS}) before the first {@code updateTBTSubInfo} frame arrives.
     */
    static float extractTbtInfoFallbackBearing(Object[] args) {
        if (args == null || args.length < 3) return Float.NaN;
        if (!Integer.valueOf(NaviBridge.TYPE_COMPASS).equals(args[0])) return Float.NaN;
        if (!(args[2] instanceof Number)) return Float.NaN;
        return clusterToBearingDeg(((Number) args[2]).floatValue());
    }

    /** Converts clockwise degrees into Android Auto {@code bearing_e6} in {@code [0, 359_999_999]}, or {@code -1}. */
    static int toBearingE6(float bearingDeg) {
        if (Float.isNaN(bearingDeg) || Float.isInfinite(bearingDeg)) return -1;
        double norm = ((bearingDeg % 360.0d) + 360.0d) % 360.0d;
        long e6 = Math.round(norm * 1000000.0d);
        if (e6 < 0L || e6 >= 360000000L) e6 = 0L;
        return (int) e6;
    }

    /** Returns {@code in} with {@code type} appended once if not already present. */
    static int[] withSensor(int[] in, int type) {
        if (in == null) return new int[] { type };
        for (int i = 0; i < in.length; i++) {
            if (in[i] == type) return in;
        }
        int[] out = new int[in.length + 1];
        System.arraycopy(in, 0, out, 0, in.length);
        out[in.length] = type;
        return out;
    }

    /** Resolves {@code SensorSource.nativeReportCompassData(int, boolean, int, boolean, int)}. */
    static Method findReportCompassMethod(Object sensorSource) throws NoSuchMethodException {
        for (Class<?> c = sensorSource.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("nativeReportCompassData",
                        int.class, boolean.class, int.class, boolean.class, int.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // check superclass
            }
        }
        throw new NoSuchMethodException("SensorSource.nativeReportCompassData(IZIZI)I");
    }

    /** Broadcasts a clockwise true bearing from {@code com.mnsoft.navi} to {@code HKMC_GPM}. */
    static void broadcastCarCompass(Context context, float bearingDeg) {
        if (context == null || Float.isNaN(bearingDeg) || Float.isInfinite(bearingDeg)) return;
        try {
            Intent i = new Intent(ACTION_CAR_COMPASS);
            i.setPackage(Wireless.GPM);
            i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            i.putExtra(EXTRA_BEARING_DEG, bearingDeg);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** Asks {@code com.mnsoft.navi} to immediately broadcast its latest cached car compass heading. */
    static void requestCarCompass(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(ACTION_REQUEST_COMPASS);
            i.setPackage(Wireless.NAVIGATION);
            i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    static Coordinator installGpm(final ModContext mod, final LoadedApp app) {
        final Coordinator coord = new Coordinator(WARMUP_MS, REPEAT_MS);
        final ClassLoader cl = app.classLoader;
        ensureReceiver(app.context, coord);
        requestCarCompass(app.context);

        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!mod.enabled() || !mod.settingBoolean("aa_compass", true)) return;
                ensureReceiver(app.context, coord);
                if (p.args != null && p.args.length >= 3 && p.args[2] instanceof int[]) {
                    p.args[2] = withSensor((int[]) p.args[2], SENSOR_COMPASS);
                }
            }

            @Override
            protected void afterHookedMethod(Param p) {
                if (!Boolean.TRUE.equals(p.getResult())) return;
                if (!mod.enabled() || !mod.settingBoolean("aa_compass", true) || p.thisObject == null) return;
                try {
                    final Object sensorSource = p.thisObject;
                    final Method report = findReportCompassMethod(sensorSource);
                    coord.startSession(mod, new Sink() {
                        @Override
                        public int sendCompass(int bearingE6) throws Exception {
                            Object r = report.invoke(sensorSource,
                                    Integer.valueOf(bearingE6),
                                    Boolean.FALSE, Integer.valueOf(0),
                                    Boolean.FALSE, Integer.valueOf(0));
                            return r instanceof Integer ? ((Integer) r).intValue() : 0;
                        }
                    });
                    requestCarCompass(app.context);
                } catch (Throwable t) {
                    mod.error("Android Auto compass init",
                            t instanceof InvocationTargetException ? ((InvocationTargetException) t).getCause() : t);
                }
            }
        });

        MethodHook ended = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                coord.stopSession();
            }
        };
        mod.hook(cl, "gpm.receiver.destroy", ended);
        mod.hook(cl, "gpm.receiver_service.disconnected", ended);
        mod.log("Android Auto: car compass hook in " + app.processName);
        return coord;
    }

    private static void ensureReceiver(Context context, final Coordinator coord) {
        if (context == null || !coord.claimReceiverRegistration()) return;
        Context appCtx = context.getApplicationContext();
        final Context regCtx = appCtx != null ? appCtx : context;
        try {
            regCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i == null || !ACTION_CAR_COMPASS.equals(i.getAction())) return;
                    if (!i.hasExtra(EXTRA_BEARING_DEG)) return;
                    coord.onCarBearing(i.getFloatExtra(EXTRA_BEARING_DEG, Float.NaN));
                }
            }, new IntentFilter(ACTION_CAR_COMPASS));
        } catch (Throwable t) {
            coord.resetReceiverRegistration();
        }
    }

    /**
     * Per-process Android Auto compass session coordinator. Runs a daemon sender thread
     * strictly while an Android Auto session is active, waking immediately on car heading
     * changes and repeating every {@code repeatMs} while stationary so Google Maps never
     * expires the compass reading (~1.5 s staleness window).
     */
    static final class Coordinator {
        private final Object lock = new Object();
        private final long warmupMs;
        private final long repeatMs;

        private int bearingE6 = -1;
        private int bearingSeq;
        private Sink sink;
        private long sessionGen;
        private Thread worker;
        private boolean receiverRegistered;

        Coordinator(long warmupMs, long repeatMs) {
            this.warmupMs = Math.max(1L, warmupMs);
            this.repeatMs = Math.max(1L, repeatMs);
        }

        boolean claimReceiverRegistration() {
            synchronized (lock) {
                if (receiverRegistered) return false;
                receiverRegistered = true;
                return true;
            }
        }

        void resetReceiverRegistration() {
            synchronized (lock) {
                receiverRegistered = false;
            }
        }

        int cachedBearingE6() {
            synchronized (lock) {
                return bearingE6;
            }
        }

        boolean isSessionActive() {
            synchronized (lock) {
                return sink != null && worker != null && worker.isAlive();
            }
        }

        void onCarBearing(float bearingDeg) {
            int e6 = toBearingE6(bearingDeg);
            if (e6 < 0) return;
            synchronized (lock) {
                if (e6 != bearingE6) {
                    bearingE6 = e6;
                    bearingSeq++;
                    lock.notifyAll();
                }
            }
        }

        void startSession(final ModContext mod, Sink newSink) {
            if (newSink == null) return;
            stopSession();
            final long myGen;
            final Thread t;
            synchronized (lock) {
                myGen = ++sessionGen;
                sink = newSink;
                t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        workerLoop(mod, myGen);
                    }
                }, "hmgapi-aa-compass");
                t.setDaemon(true);
                worker = t;
            }
            t.start();
        }

        void stopSession() {
            Thread toJoin;
            synchronized (lock) {
                sessionGen++;
                sink = null;
                toJoin = worker;
                worker = null;
                lock.notifyAll();
            }
            if (toJoin != null && toJoin != Thread.currentThread()) {
                try {
                    toJoin.join(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void workerLoop(ModContext mod, long myGen) {
            int lastSentSeq = -1;
            long lastSentAt = 0L;
            int sentCount = 0;
            boolean loggedFirstSend = false;
            boolean loggedError = false;

            while (true) {
                Sink currentSink;
                int e6ToSend;
                int seqSnapshot;
                synchronized (lock) {
                    if (sessionGen != myGen || sink == null
                            || !mod.enabled() || !mod.settingBoolean("aa_compass", true)) {
                        if (sessionGen == myGen) {
                            sink = null;
                            worker = null;
                        }
                        return;
                    }
                    if (bearingE6 < 0) {
                        waitQuietly(repeatMs);
                        continue;
                    }
                    long nowMs = System.nanoTime() / 1000000L;
                    long interval = sentCount < 2 ? warmupMs : repeatMs;
                    boolean changed = (bearingSeq != lastSentSeq);
                    long elapsed = nowMs - lastSentAt;
                    if (!changed && sentCount > 0 && elapsed < interval) {
                        waitQuietly(interval - elapsed);
                        continue;
                    }
                    currentSink = sink;
                    e6ToSend = bearingE6;
                    seqSnapshot = bearingSeq;
                }

                try {
                    currentSink.sendCompass(e6ToSend);
                    lastSentSeq = seqSnapshot;
                    lastSentAt = System.nanoTime() / 1000000L;
                    sentCount++;
                    if (!loggedFirstSend) {
                        loggedFirstSend = true;
                        mod.log("Android Auto: car compass active (" + (e6ToSend / 1000000.0f) + " deg)");
                    }
                } catch (Throwable t) {
                    if (!loggedError) {
                        loggedError = true;
                        mod.error("Android Auto compass send",
                                t instanceof InvocationTargetException ? ((InvocationTargetException) t).getCause() : t);
                    }
                    synchronized (lock) {
                        if (sessionGen == myGen && sink != null) waitQuietly(repeatMs);
                    }
                }
            }
        }

        private void waitQuietly(long ms) {
            if (ms <= 0L) return;
            try {
                lock.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
