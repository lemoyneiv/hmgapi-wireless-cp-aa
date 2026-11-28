package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * Arbitrates between stock navigation (com.mnsoft.navi) and phone turn-by-turn:
 *
 * 1. Suppresses stock TBT and unmarked intersection lane calls while Android Auto or CarPlay
 *    guides, caching Binder guidance checks for 250 ms and honoring explicit PHONE_TBT
 *    start/stop broadcasts from AaTurns and CpTurns.
 * 2. Captures stock navigation's own idle compass frames (DisplayType 8 + sub-info) and
 *    replays them immediately when phone guidance ends, so the cluster/HUD compass returns
 *    instantly even while parked, driving straight, or inside a garage.
 * 3. Injects the phone's approach countdown bar (barGraphLevel 0..90) into copies of stock
 *    navigation's speed-limit trap frames (updateTBTTrapInfo / updateTBTTrapInfoNew),
 *    preserving the car's map speed limit, cameras and warnings, and restoring the stock
 *    trap frame when guidance ends or expires.
 */
final class NaviBridge {
    static final String ACTION_PHONE_TBT = "hmgapi.mods.wireless.PHONE_TBT";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_BAR = "bar";
    static final String SOURCE_AA = "aa";
    static final String SOURCE_CP = "cp";

    static final int TYPE_COMPASS = 8;
    static final int TRAP_INT_ARGS = 17;
    static final int TRAP_BAR_INDEX = 11;
    static final long LEASE_MS = 6000;
    static final long END_GRACE_MS = 1500;
    static final long IPC_CACHE_MS = 250;
    static final long MIN_TRAP_GAP_MS = 200;
    static final long COMPASS_FOLLOWUP_MS = 300;
    static final long COMPASS_HEARTBEAT_MS = 5000;

    static final String PT_TBT_INFO = "update_tbt_info";
    static final String PT_SUB_INFO = "update_tbt_sub_info";
    static final String PT_SUB_INFO_B = "update_tbt_sub_info_b";
    static final String[] COMPASS_POINTS = { PT_TBT_INFO, PT_SUB_INFO, PT_SUB_INFO_B };
    static final String PT_TRAP_INT = "framework.navigation_cluster.update_tbt_trap_info";
    static final String PT_TRAP_NEW = "framework.navigation_cluster.update_tbt_trap_info_new";
    static final String PT_TRAP_CLS = "framework.navi_tbt_trap_info";
    static final String PT_TRAP_CTOR = "framework.navi_tbt_trap_info.new";
    static final String PT_TRAP_BAR = "framework.navi_tbt_trap_info.bar_graph_level";

    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<Boolean>();

    /** Host tests can override monotonic milliseconds; < 0 uses System.nanoTime. */
    static long testNow = -1;

    static long now() {
        return testNow >= 0 ? testNow : System.nanoTime() / 1000000L;
    }

    static final class Call {
        final Method method;
        final Object target;
        final Object[] args;

        Call(Method method, Object target, Object[] args) {
            this.method = method;
            this.target = target;
            this.args = args;
        }

        void invoke() throws Exception {
            if (method != null && target != null && args != null) method.invoke(target, args);
        }
    }

    private final ModContext mod;
    private final Context context;
    private final boolean aaGuidance;
    private Handler handler;

    // Lease and grace windows per phone source ("aa", "cp")
    private long aaLeaseUntil, cpLeaseUntil;
    private long aaEndedGraceUntil, cpEndedGraceUntil;
    private int aaBar = -1, cpBar = -1;

    // Cached IPC guidance state (used when context != null)
    private long ipcCheckedAt = -IPC_CACHE_MS - 1;
    private boolean ipcGuiding;
    private boolean wasGuiding;

    // Latest stock compass frames & dead-reckoned car heading captured from com.mnsoft.navi
    private boolean stockShowingCompass;
    private Call compassTbt, compassSub;
    private int compassReplayGen;
    private float lastCompassDeg = Float.NaN;
    private boolean hasSubCompass;
    private long lastCompassBroadcastAt = -COMPASS_HEARTBEAT_MS;

    // Latest stock trap frames captured from com.mnsoft.navi
    private Call trapInt, trapObj;
    private Constructor<?> trapCtor;
    private Field trapBarField;
    private Field[] trapIntFields;
    private long trapSeq;
    private long lastTrapSendAt = -MIN_TRAP_GAP_MS;
    private int lastAppliedBar = -1;
    private boolean trapSendQueued;
    private boolean trapErrorLogged;

    private final Runnable trapSendRun = new Runnable() {
        @Override
        public void run() {
            synchronized (NaviBridge.this) {
                trapSendQueued = false;
            }
            resendTrap();
        }
    };

    private final Runnable leaseExpireRun = new Runnable() {
        @Override
        public void run() {
            onTick(now());
        }
    };

    NaviBridge(ModContext mod, Context context, boolean aaGuidance, Handler testHandler) {
        this.mod = mod;
        this.context = context;
        this.aaGuidance = aaGuidance;
        this.handler = testHandler;
    }

    static NaviBridge install(final ModContext mod, final LoadedApp app, final boolean aaGuidance, String[] tbtPoints) {
        Handler bg = null;
        if (app.context != null) {
            HandlerThread ht = new HandlerThread("hmgapi-navi-bridge");
            ht.start();
            bg = new Handler(ht.getLooper());
        }
        final NaviBridge bridge = new NaviBridge(mod, app.context, aaGuidance, bg);
        bridge.registerBridgeReceiver(true);
        for (final String m : tbtPoints) {
            final Holder h = new Holder();
            Member mem = mod.hook(null, "framework.navigation_cluster." + m, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (REPLAYING.get() != null) return;
                    Method method = p.method instanceof Method ? (Method) p.method : h.method;
                    bridge.onStockTbtCall(m, method, p.thisObject, p.args, now());
                    if (bridge.shouldSuppress(now())) p.setResult(null);
                }
            });
            if (mem instanceof Method) h.method = (Method) mem;
        }
        bridge.installTrapHooks();
        return bridge;
    }

    /** Non-suppressing compass listener used when tbt=false and aa_compass=true. */
    static NaviBridge installCompassOnly(final ModContext mod, final LoadedApp app) {
        final NaviBridge bridge = new NaviBridge(mod, app.context, false, null);
        bridge.registerBridgeReceiver(false);
        for (final String m : COMPASS_POINTS) {
            mod.hook(null, "framework.navigation_cluster." + m, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (REPLAYING.get() != null) return;
                    bridge.recordCarCompass(m, p.args, now());
                }
            });
        }
        return bridge;
    }

    private void registerBridgeReceiver(final boolean listenPhoneTbt) {
        if (context == null) return;
        try {
            IntentFilter f = new IntentFilter();
            if (listenPhoneTbt) f.addAction(ACTION_PHONE_TBT);
            f.addAction(AaCompass.ACTION_REQUEST_COMPASS);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i == null) return;
                    String act = i.getAction();
                    if (listenPhoneTbt && ACTION_PHONE_TBT.equals(act)) {
                        String src = i.getStringExtra(EXTRA_SOURCE);
                        boolean active = i.getBooleanExtra(EXTRA_ACTIVE, false);
                        int bar = i.getIntExtra(EXTRA_BAR, -1);
                        onPhoneTbt(src, active, bar, now());
                    } else if (AaCompass.ACTION_REQUEST_COMPASS.equals(act)) {
                        onCompassRequested(now());
                    }
                }
            }, f);
        } catch (Throwable t) {
            mod.error("navigation: bridge receiver", t);
        }
    }

    private static final class Holder {
        volatile Method method;
    }

    private void installTrapHooks() {
        int hooked = 0;
        if (Points.has(PT_TRAP_INT)) {
            try {
                final Holder h = new Holder();
                Member mem = mod.hook(null, PT_TRAP_INT, new MethodHook() {
                    private final ThreadLocal<Integer> usedBar = new ThreadLocal<Integer>();

                    @Override
                    protected void beforeHookedMethod(Param p) {
                        if (REPLAYING.get() != null) return;
                        usedBar.remove();
                        Method method = p.method instanceof Method ? (Method) p.method : h.method;
                        int applied = onStockTrapInt(method, p.thisObject, p.args, now());
                        if (applied >= 0) usedBar.set(applied);
                    }

                    @Override
                    protected void afterHookedMethod(Param p) {
                        if (REPLAYING.get() != null) return;
                        Integer applied = usedBar.get();
                        if (applied == null) return;
                        usedBar.remove();
                        checkTrapDrift(applied, now());
                    }
                });
                if (mem instanceof Method) h.method = (Method) mem;
                hooked++;
            } catch (Throwable t) {
                mod.error("navigation: update_tbt_trap_info hook", t);
            }
        }
        if (Points.has(PT_TRAP_NEW) && Points.has(PT_TRAP_CLS) && Points.has(PT_TRAP_CTOR) && Points.has(PT_TRAP_BAR)) {
            try {
                Class<?> cls = Points.cls(null, PT_TRAP_CLS);
                Constructor<?> ctor = Points.ctor(null, PT_TRAP_CTOR);
                Field barField = Points.field(null, PT_TRAP_BAR);
                Field[] allInts = verifyAllIntFields(cls);
                if (allInts != null && barField != null && ctor != null) {
                    this.trapCtor = ctor;
                    this.trapBarField = barField;
                    this.trapIntFields = allInts;
                    final Holder h = new Holder();
                    Member mem = mod.hook(null, PT_TRAP_NEW, new MethodHook() {
                        private final ThreadLocal<Integer> usedBar = new ThreadLocal<Integer>();

                        @Override
                        protected void beforeHookedMethod(Param p) {
                            if (REPLAYING.get() != null) return;
                            usedBar.remove();
                            Method method = p.method instanceof Method ? (Method) p.method : h.method;
                            int applied = onStockTrapObj(method, p.thisObject, p.args, now());
                            if (applied >= 0) usedBar.set(applied);
                        }

                        @Override
                        protected void afterHookedMethod(Param p) {
                            if (REPLAYING.get() != null) return;
                            Integer applied = usedBar.get();
                            if (applied == null) return;
                            usedBar.remove();
                            checkTrapDrift(applied, now());
                        }
                    });
                    if (mem instanceof Method) h.method = (Method) mem;
                    hooked++;
                }
            } catch (Throwable t) {
                mod.error("navigation: update_tbt_trap_info_new hook", t);
            }
        }
        if (hooked > 0) {
            mod.log("navigation: approach countdown bar spliced into stock trap frames (" + hooked + " hook" + (hooked == 1 ? "" : "s") + ")");
        }
    }

    /** Returns all non-static int fields if every instance field on cls is a mutable int; else null. */
    static Field[] verifyAllIntFields(Class<?> cls) {
        if (cls == null) return null;
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || f.isSynthetic()) continue;
                if (Modifier.isFinal(m) || f.getType() != int.class) return null;
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out.isEmpty() ? null : out.toArray(new Field[out.size()]);
    }

    private Object copyTrapObject(Object src, int overrideBar) throws Exception {
        Object dst = trapCtor.newInstance();
        for (Field f : trapIntFields) f.setInt(dst, f.getInt(src));
        if (overrideBar >= 0) trapBarField.setInt(dst, overrideBar);
        return dst;
    }

    /** Records stock compass frames before suppression and forwards car bearing updates to GPM. */
    void onStockTbtCall(String point, Method method, Object target, Object[] args, long nowMs) {
        if (args == null) return;
        float toSend;
        synchronized (this) {
            if (PT_TBT_INFO.equals(point) && args.length >= 1 && args[0] instanceof Integer) {
                int type = (Integer) args[0];
                stockShowingCompass = (type == TYPE_COMPASS);
                if (stockShowingCompass && method != null && target != null) {
                    compassTbt = new Call(method, target, args.clone());
                }
            } else if ((PT_SUB_INFO.equals(point) || PT_SUB_INFO_B.equals(point)) && args.length >= 9) {
                if ((stockShowingCompass || Integer.valueOf(-3).equals(args[1]))
                        && Integer.valueOf(0).equals(args[8]) && method != null && target != null) {
                    compassSub = new Call(method, target, args.clone());
                }
            }
            toSend = recordCompassLocked(point, args, nowMs);
        }
        if (!Float.isNaN(toSend)) AaCompass.broadcastCarCompass(context, toSend);
    }

    void recordCarCompass(String point, Object[] args, long nowMs) {
        float toSend;
        synchronized (this) {
            toSend = recordCompassLocked(point, args, nowMs);
        }
        if (!Float.isNaN(toSend)) AaCompass.broadcastCarCompass(context, toSend);
    }

    void onCompassRequested(long nowMs) {
        float toSend = Float.NaN;
        synchronized (this) {
            if (!Float.isNaN(lastCompassDeg) && mod.enabled() && mod.settingBoolean("aa_compass", true)) {
                lastCompassBroadcastAt = nowMs;
                toSend = lastCompassDeg;
            }
        }
        if (!Float.isNaN(toSend)) AaCompass.broadcastCarCompass(context, toSend);
    }

    synchronized float lastCompassDeg() {
        return lastCompassDeg;
    }

    private float recordCompassLocked(String point, Object[] args, long nowMs) {
        if (args == null) return Float.NaN;
        float deg = Float.NaN;
        if (PT_SUB_INFO.equals(point) || PT_SUB_INFO_B.equals(point)) {
            deg = AaCompass.extractSubInfoBearing(args);
            if (!Float.isNaN(deg)) hasSubCompass = true;
        } else if (PT_TBT_INFO.equals(point) && !hasSubCompass) {
            deg = AaCompass.extractTbtInfoFallbackBearing(args);
        }
        if (Float.isNaN(deg)) return Float.NaN;
        boolean changed = Float.isNaN(lastCompassDeg) || angularDiff(deg, lastCompassDeg) >= 0.05f;
        lastCompassDeg = deg;
        if (!mod.enabled() || !mod.settingBoolean("aa_compass", true)) return Float.NaN;
        if (!changed && nowMs - lastCompassBroadcastAt < COMPASS_HEARTBEAT_MS) return Float.NaN;
        lastCompassBroadcastAt = nowMs;
        return deg;
    }

    private static float angularDiff(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    /** True when stock TBT / unmarked lane calls should be suppressed in favor of phone guidance. */
    boolean shouldSuppress(long nowMs) {
        boolean guiding;
        boolean endedTransition = false;
        boolean barResetNeeded = false;
        synchronized (this) {
            guiding = isGuidingLocked(nowMs);
            if (wasGuiding && !guiding) {
                endedTransition = true;
                if (lastAppliedBar >= 0) {
                    lastAppliedBar = -1;
                    barResetNeeded = true;
                }
            }
            wasGuiding = guiding;
        }
        if (endedTransition) scheduleCompassReplay();
        if (barResetNeeded) queueTrapSend(0);
        return guiding;
    }

    private boolean isGuidingLocked(long nowMs) {
        if (nowMs < aaLeaseUntil || nowMs < cpLeaseUntil) return true;
        if (context == null) {
            boolean cp = nowMs >= cpEndedGraceUntil && Car.carPlayGuiding(null);
            boolean aa = aaGuidance && nowMs >= aaEndedGraceUntil && Car.androidAutoGuiding(null);
            return cp || aa;
        }
        if (nowMs - ipcCheckedAt >= IPC_CACHE_MS) {
            ipcCheckedAt = nowMs;
            boolean cp = nowMs >= cpEndedGraceUntil && Car.carPlayGuiding(context);
            boolean aa = aaGuidance && nowMs >= aaEndedGraceUntil && Car.androidAutoGuiding(context);
            ipcGuiding = cp || aa;
        }
        return ipcGuiding;
    }

    /** Handles PHONE_TBT broadcasts from AaTurns ("aa") and CpTurns ("cp"). */
    void onPhoneTbt(String source, boolean active, int bar, long nowMs) {
        boolean triggerCompass = false;
        boolean triggerTrap = false;
        long nextExpiry = 0;
        synchronized (this) {
            boolean isCp = SOURCE_CP.equals(source);
            boolean isAa = SOURCE_AA.equals(source) || !isCp;
            if (active) {
                if (isCp) {
                    cpLeaseUntil = nowMs + LEASE_MS;
                    cpEndedGraceUntil = 0;
                    cpBar = sanitizeBar(bar);
                } else if (isAa) {
                    aaLeaseUntil = nowMs + LEASE_MS;
                    aaEndedGraceUntil = 0;
                    aaBar = sanitizeBar(bar);
                }
                wasGuiding = true;
                compassReplayGen++; // cancel any pending compass follow-up
                nextExpiry = Math.max(aaLeaseUntil, cpLeaseUntil) - nowMs + 100;
            } else {
                if (isCp) {
                    cpLeaseUntil = 0;
                    cpEndedGraceUntil = nowMs + END_GRACE_MS;
                    cpBar = -1;
                } else {
                    aaLeaseUntil = 0;
                    aaEndedGraceUntil = nowMs + END_GRACE_MS;
                    aaBar = -1;
                }
                ipcCheckedAt = -IPC_CACHE_MS - 1; // force fresh IPC check after grace
                boolean stillGuiding = isGuidingLocked(nowMs);
                if (wasGuiding && !stillGuiding) {
                    wasGuiding = false;
                    triggerCompass = true;
                }
            }
            int eff = effectiveBarLocked(nowMs);
            if (eff != lastAppliedBar) {
                lastAppliedBar = eff;
                triggerTrap = true;
            }
        }
        if (handler != null) {
            handler.removeCallbacks(leaseExpireRun);
            if (nextExpiry > 0) handler.postDelayed(leaseExpireRun, nextExpiry);
        }
        if (triggerCompass) scheduleCompassReplay();
        if (triggerTrap) queueTrapSend(0);
    }

    void onTick(long nowMs) {
        boolean triggerCompass = false;
        boolean triggerTrap = false;
        synchronized (this) {
            boolean guiding = isGuidingLocked(nowMs);
            if (wasGuiding && !guiding) {
                wasGuiding = false;
                triggerCompass = true;
            }
            int eff = effectiveBarLocked(nowMs);
            if (eff != lastAppliedBar) {
                lastAppliedBar = eff;
                triggerTrap = true;
            }
        }
        if (triggerCompass) scheduleCompassReplay();
        if (triggerTrap) queueTrapSend(0);
    }

    private static int sanitizeBar(int bar) {
        return bar >= 0 && bar <= 100 ? bar : -1;
    }

    private int effectiveBarLocked(long nowMs) {
        if (nowMs < cpLeaseUntil && cpBar >= 0) return cpBar;
        if (nowMs < aaLeaseUntil && aaBar >= 0) return aaBar;
        return -1;
    }

    synchronized int onStockTrapInt(Method method, Object target, Object[] args, long nowMs) {
        if (args == null || args.length != TRAP_INT_ARGS || !(args[TRAP_BAR_INDEX] instanceof Integer)) return -1;
        int stockBar = (Integer) args[TRAP_BAR_INDEX];
        if (stockBar < 0 || stockBar > 100) return -1;
        if (method != null && target != null) {
            trapInt = new Call(method, target, args.clone());
            trapSeq++;
        }
        int eff = effectiveBarLocked(nowMs);
        if (eff >= 0) {
            args[TRAP_BAR_INDEX] = Integer.valueOf(eff);
            lastAppliedBar = eff;
            return eff;
        }
        return -1;
    }

    synchronized int onStockTrapObj(Method method, Object target, Object[] args, long nowMs) {
        if (args == null || args.length != 1 || args[0] == null || trapBarField == null) return -1;
        try {
            int stockBar = trapBarField.getInt(args[0]);
            if (stockBar < 0 || stockBar > 100) return -1;
            if (method != null && target != null) {
                Object saved = copyTrapObject(args[0], -1);
                trapObj = new Call(method, target, new Object[] { saved });
                trapSeq++;
            }
            int eff = effectiveBarLocked(nowMs);
            if (eff >= 0) {
                args[0] = copyTrapObject(args[0], eff);
                lastAppliedBar = eff;
                return eff;
            }
        } catch (Throwable t) {
            logTrapError(t);
        }
        return -1;
    }

    private void checkTrapDrift(int applied, long nowMs) {
        boolean differ;
        synchronized (this) {
            differ = applied != effectiveBarLocked(nowMs);
        }
        if (differ) queueTrapSend(MIN_TRAP_GAP_MS);
    }

    private void queueTrapSend(long delayMs) {
        if (handler == null) {
            resendTrap();
            return;
        }
        synchronized (this) {
            if (trapSendQueued) return;
            trapSendQueued = true;
        }
        if (delayMs <= 0) handler.post(trapSendRun);
        else handler.postDelayed(trapSendRun, delayMs);
    }

    void resendTrap() {
        Call intCall = null, objCall = null;
        long seqBefore;
        synchronized (this) {
            if (trapInt == null && trapObj == null) return;
            long nowMs = now();
            long wait = lastTrapSendAt + MIN_TRAP_GAP_MS - nowMs;
            if (wait > 0 && handler != null) {
                if (!trapSendQueued) {
                    trapSendQueued = true;
                    handler.postDelayed(trapSendRun, wait);
                }
                return;
            }
            seqBefore = trapSeq;
            int eff = effectiveBarLocked(nowMs);
            if (trapInt != null) {
                Object[] copy = trapInt.args.clone();
                if (eff >= 0) copy[TRAP_BAR_INDEX] = Integer.valueOf(eff);
                intCall = new Call(trapInt.method, trapInt.target, copy);
            }
            if (trapObj != null) {
                try {
                    Object copy = copyTrapObject(trapObj.args[0], eff);
                    objCall = new Call(trapObj.method, trapObj.target, new Object[] { copy });
                } catch (Throwable t) {
                    logTrapError(t);
                }
            }
            lastTrapSendAt = nowMs;
        }
        REPLAYING.set(Boolean.TRUE);
        try {
            if (intCall != null) intCall.invoke();
            if (objCall != null) objCall.invoke();
        } catch (Throwable t) {
            logTrapError(t);
        } finally {
            REPLAYING.remove();
        }
        boolean changedMeanwhile;
        synchronized (this) {
            changedMeanwhile = (trapSeq != seqBefore);
        }
        if (changedMeanwhile && handler != null) queueTrapSend(MIN_TRAP_GAP_MS);
    }

    private void scheduleCompassReplay() {
        final int gen;
        synchronized (this) {
            gen = ++compassReplayGen;
        }
        if (handler == null) {
            replayCompass(gen);
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                replayCompass(gen);
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                replayCompass(gen);
            }
        }, COMPASS_FOLLOWUP_MS);
    }

    void replayCompass(int gen) {
        Call tbt, sub;
        synchronized (this) {
            if (gen != compassReplayGen || isGuidingLocked(now())) return;
            tbt = compassTbt == null ? null : new Call(compassTbt.method, compassTbt.target, compassTbt.args.clone());
            sub = compassSub == null ? null : new Call(compassSub.method, compassSub.target, compassSub.args.clone());
        }
        if (tbt == null && sub == null) return;
        REPLAYING.set(Boolean.TRUE);
        try {
            if (tbt != null) tbt.invoke();
            if (sub != null) sub.invoke();
        } catch (Throwable t) {
            mod.error("navigation: compass replay", t);
        } finally {
            REPLAYING.remove();
        }
    }

    private void logTrapError(Throwable t) {
        if (trapErrorLogged) return;
        trapErrorLogged = true;
        mod.error("navigation: trap bar", t);
    }

    /** Sends a PHONE_TBT update from GPM (AaTurns) to com.mnsoft.navi. */
    static void broadcastFromPhone(Context c, String source, boolean active, int bar) {
        if (c == null) return;
        try {
            Intent i = new Intent(ACTION_PHONE_TBT);
            i.setPackage(Wireless.NAVIGATION);
            i.putExtra(EXTRA_SOURCE, source);
            i.putExtra(EXTRA_ACTIVE, active);
            i.putExtra(EXTRA_BAR, bar);
            c.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
