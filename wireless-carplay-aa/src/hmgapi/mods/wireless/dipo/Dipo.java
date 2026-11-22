package hmgapi.mods.wireless.dipo;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.lang.reflect.Field;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The Wireless mod in DiPO (the CarPlay app): its app part, linked against DiPO's
 * libraries (com.tcc.iap2, com.broadcom.bt). Wireless.appLoaded starts it.
 */
public final class Dipo {
    private Dipo() {}

    static final String IAP2_ACTION = "com.remote.service.IAP2SERVICE";
    static final String CP_FIRST_CONNECTED = "com.android.app.PhoneProjection.CP_FIRST_CONNECTED";
    static final int LISTENER_ROUTE_GUIDANCE = 4096;
    static final int RG_UPDATE = 120, RG_MANEUVER = 121, RG_LANES = 122;
    static final String[] RG_COMPONENTS = { "RouteGuidanceComponent0" };
    static final long NP_DELAY = 1500;
    /** Window after a source switch or active route during which DiPO's own Stop triggers re-subscribe (#24). */
    static final long STOP_RECOVER_WINDOW_MS = 3500;

    static ModContext mod;
    static LoadedApp app;
    static volatile IIap2Service iap2;
    static boolean tbt, bridge, nowPlaying;
    static Handler npHandler;
    static volatile Object npManager;
    private static volatile Field sDipoIap2Field;
    private static volatile Class<?> sDipoIap2Owner;
    static Handler turnsHandler;
    static CpTurns turns;
    static RgListener rg;
    static RgWatch rgWatch;
    private static Handler rgHandler;
    private static volatile long lastSourceSwitchAt = -10000L;

    static final ThreadLocal<String> SESSION = new ThreadLocal<String>();
    static final ThreadLocal<Boolean> NO_ADDRESS = new ThreadLocal<Boolean>();

    private static final Runnable serializedRestart = new Runnable() {
        @Override
        public void run() {
            restartRouteGuidance();
        }
    };

    private static final Runnable recoverFromDipoStop = new Runnable() {
        @Override
        public void run() {
            IIap2Service s = iap2;
            if (s == null) return;
            try {
                s.Iap2SvcStartRouteGuidanceUpdate(RG_COMPONENTS, true, true);
                RgWatch w = rgWatch;
                if (w != null) w.started();
                log("CarPlay: re-subscribed route guidance after DiPO's stop while CarPlay TBT remained enabled");
            } catch (Throwable ignored) {
            }
        }
    };

    static void log(String m) {
        if (mod != null) mod.log(m);
    }

    static void error(String m, Throwable t) {
        if (mod != null) mod.error(m, t);
    }

    public static void start(ModContext m, LoadedApp a) {
        mod = m;
        app = a;
        tbt = m.settingBoolean("tbt", true);
        bridge = m.settingBoolean("bridge", true);
        nowPlaying = bridge && m.settingBoolean("now_playing", true);
        CarPlayLog.on = m.settingBoolean("carplay_log", false);
        CarPlayLog.apply("start");
        if (bridge) m.nativePatch(a, "libCarPlayCore.so");
        if (m.settingBoolean("audio", true)) {
            m.nativePatch(a, "libCarPlayAudio.so");
            m.nativePatch(a, "libCarPlayUtils.so");
        }
        if (!tbt && !bridge) return;
        rgWatch();
        if (tbt) turnByTurn();
        if (bridge) bridge();
        Intent i = new Intent(IAP2_ACTION);
        boolean bound = a.context.bindService(i, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder b) {
                IIap2Service s = IIap2Service.Stub.asInterface(b);
                iap2 = s;
                if (tbt) registerRouteGuidance(s);
                if (bridge) Bridge.onIap2Bound(s);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                iap2 = null;
                if (bridge) Bridge.onIap2ServiceLost();
                log("DiPO: iAP2 server gone");
            }
        }, Context.BIND_AUTO_CREATE);
        log("DiPO: iAP2 server " + (bound ? "binding" : "can't be bound"));
    }

    private static void turnByTurn() {
        if (Car.stepOfSpec() >= 999) {
            log("CarPlay: stepofspec is 999 or more, DiPO's own turn-by-turn runs (HMG-API's stays off)");
            tbt = false;
            return;
        }
        Cluster c = Cluster.of(app.context);
        if (c == null) {
            tbt = false;
            return;
        }
        HandlerThread t = new HandlerThread("hmgapi-cp-tbt");
        t.start();
        turnsHandler = new Handler(t.getLooper());
        turns = new CpTurns(app.context, c, new Runnable() {
            @Override
            public void run() {
                lastSourceSwitchAt = SystemClock.elapsedRealtime();
                RgWatch w = rgWatch;
                if (w != null) w.started();
                queueRestartRouteGuidance();
            }
        }, turnsHandler);
        turns.unitCacheMs = 2000L;
        turns.standardTurns = mod.settingBoolean("tbt_standard", true);
        log("CarPlay: turn-by-turn to the cluster (" + (turns.standardTurns ? "enhanced native scenes" : "projection icons") + ")");
        rg = new RgListener();
        mod.hook(app.classLoader, "dipo.iap2_proxy.stop_route_guidance", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                long elapsed = SystemClock.elapsedRealtime();
                boolean sourceSwitching = elapsed - lastSourceSwitchAt < STOP_RECOVER_WINDOW_MS;
                boolean stillGuiding = Car.carPlayGuiding(app.context);
                log("CarPlay: DiPO stopped route guidance (carPlayGuiding=" + stillGuiding + ", sourceSwitch=" + sourceSwitching + ")");
                if (sourceSwitching || stillGuiding) {
                    // Issue #24: when switching from Apple Maps to Google Maps/Waze, DiPO emits
                    // StopRouteGuidanceUpdate for the old route ~70 ms after TurnByTurn: ENABLE.
                    // Re-subscribe cleanly instead of disarming RgWatch!
                    if (rgHandler != null) {
                        rgHandler.removeCallbacks(recoverFromDipoStop);
                        rgHandler.postDelayed(recoverFromDipoStop, 300);
                    }
                    return;
                }
                RgWatch w = rgWatch;
                if (w != null) w.stopped();
                post(new Runnable() {
                    @Override
                    public void run() {
                        turns.stop();
                    }
                });
            }
        });
    }

    static void post(Runnable r) {
        final Runnable run = r;
        turnsHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    run.run();
                } catch (Throwable x) {
                    error("CarPlay turn-by-turn", x);
                }
            }
        });
    }

    private static void queueRestartRouteGuidance() {
        if (rgHandler != null) {
            rgHandler.removeCallbacks(serializedRestart);
            rgHandler.post(serializedRestart);
        } else if (turnsHandler != null) {
            post(serializedRestart);
        }
    }

    static void registerRouteGuidance(IIap2Service s) {
        try {
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_ROUTE_GUIDANCE);
            info.onSetListenerName("HMGAPI_RG");
            log("CarPlay: Iap2SvcInit(HMGAPI_RG, 0x1000) = " + s.Iap2SvcInit(rg, info));
        } catch (Throwable t) {
            error("CarPlay: route guidance listener", t);
        }
    }

    static void restartRouteGuidance() {
        IIap2Service s = iap2;
        if (s == null) return;
        try {
            s.Iap2SvcStopRouteGuidanceUpdate(RG_COMPONENTS);
            Thread.sleep(200);
            s.Iap2SvcStartRouteGuidanceUpdate(RG_COMPONENTS, true, true);
        } catch (Throwable ignored) {
        }
    }

    static final class RgListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {}

        @Override
        public void disconnected(final int component) {
            if (component == 2 || (bridge && Bridge.dipoIgnoreDisconnect(component))) return;
            post(new Runnable() {
                @Override
                public void run() {
                    turns.disconnected();
                }
            });
        }

        @Override
        public void notify(final int what, int a1, int a2, final Iap2NotiMsg msg) {
            if ((what != RG_UPDATE && what != RG_MANEUVER && what != RG_LANES) || msg == null) return;
            RgWatch w = rgWatch;
            if (w != null && what != RG_LANES) w.update();
            post(new Runnable() {
                @Override
                public void run() {
                    if (what == RG_UPDATE) turns.routeGuidance(msg);
                    else if (what == RG_MANEUVER) turns.maneuver(msg);
                    else turns.lanes(msg);
                }
            });
        }
    }

    private static void bridge() {
        final ClassLoader cl = app.classLoader;
        Bridge.cl = cl;
        Bridge.context = app.context;
        mod.hook(cl, "dipo.btcm.register_listener", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!Bridge.isOurs(p.args[0])) Bridge.onBtcmReady((HfDeviceBTCMManager) p.thisObject);
            }
        });
        mod.hook(cl, "dipo.carplay.session_created", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                CarPlayLog.apply("session");
                Bridge.onCpSessionCreated(p.thisObject);
            }
        });
        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionStarted((Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionFinalized((Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.iap_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpIapIn(p.thisObject, (byte[]) p.args[0], (Integer) p.args[1]);
            }
        });
        if (Points.has("dipo.carplay.disable_bluetooth")) {
            mod.hook(cl, "dipo.carplay.disable_bluetooth", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (!Bridge.sCpWireless || "0".equals(Bridge.prop("persist.ev6.cpwl.keepbt", "1"))) return;
                    log("DiPO: Bluetooth kept up for wireless CarPlay (disableBluetooth skipped)");
                    p.setResult(null);
                }
            });
        } else {
            log("DiPO: this car's map has no dipo.carplay.disable_bluetooth: Bluetooth isn't kept up for wireless CarPlay");
        }
        mod.hook(cl, "dipo.utils.is_support_wireless_carplay", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                p.setResult(false);
            }
        });
        mod.hook(cl, "dipo.iap2_listener.connected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    Object manager = Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject));
                    Points.call(cl, "dipo.iap2.set_notify_on", null, manager, true);
                    npManager = manager;
                    if (nowPlaying) nowPlaying(manager, true);
                    p.setResult(null);
                } else if (c == Bridge.COMPONENT_BT_CARPLAY) {
                    p.args[0] = Bridge.COMPONENT_USB;
                }
            }
        });
        mod.hook(cl, "dipo.iap2_listener.disconnected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (nowPlaying && c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    nowPlaying(Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject)), false);
                }
                if (Bridge.dipoIgnoreDisconnect(c)) p.setResult(null);
            }
        });
        mod.hook(cl, "dipo.iap2_listener.notify", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Bridge.dipoDropNoti((Integer) p.args[0])) p.setResult(null);
            }
        });
        reconnectFix(cl);
        firstConnectNotice();
        {
            IntentFilter f = new IntentFilter(Bridge.ACTION_IAP2_CONNECTFAIL);
            f.addAction(Bridge.ACTION_IAP2_CONNECTSUCCESS);
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int comp = i.getIntExtra(Bridge.EXTRA_IAP2_COMPONENT, -1);
                    if (Bridge.ACTION_IAP2_CONNECTSUCCESS.equals(i.getAction())) Bridge.onIap2ConnectSuccess(comp);
                    else Bridge.onIap2ConnectFail(comp);
                }
            }, f);
        }
        if (nowPlaying) {
            HandlerThread t = new HandlerThread("hmgapi-cp-np");
            t.start();
            npHandler = new Handler(t.getLooper());
        }
        log("bridge: wireless CarPlay Bluetooth bridge in DiPO");
    }

    private static void nowPlaying(final Object manager, final boolean start) {
        npHandler.removeCallbacksAndMessages(null);
        npHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                IIap2Service s = dipoIap2(manager);
                if (s == null) {
                    log("Now Playing: no iAP2 connection");
                    return;
                }
                try {
                    s.Iap2SvcNowPlayingStop();
                    if (start) s.Iap2SvcNowPlayingStart();
                    log(start ? "Now Playing: CarPlay channel session up, asking the phone again" : "Now Playing: CarPlay channel session ended, stopped");
                } catch (Throwable t) {
                    error("Now Playing", t);
                }
            }
        }, start ? NP_DELAY : 0);
    }

    static void askAgain() {
        if (tbt) queueRestartRouteGuidance();
        if (nowPlaying && npHandler != null) nowPlaying(npManager, true);
    }

    private static void rgWatch() {
        HandlerThread t = new HandlerThread("hmgapi-cp-rg");
        t.start();
        rgHandler = new Handler(t.getLooper());
        rgWatch = new RgWatch(new RgWatch.Host() {
            @Override
            public long now() {
                return SystemClock.elapsedRealtime();
            }

            @Override
            public Object schedule(Runnable r, long delayMs) {
                rgHandler.postDelayed(r, delayMs);
                return r;
            }

            @Override
            public void cancel(Object token) {
                rgHandler.removeCallbacks((Runnable) token);
            }

            @Override
            public void askAgain() {
                Dipo.askAgain();
            }

            @Override
            public void restart() {
                if (tbt) queueRestartRouteGuidance();
            }

            @Override
            public void log(String m) {
                Dipo.log("CarPlay: " + m);
            }
        }, tbt);
    }

    static void rgSessionUp() {
        RgWatch w = rgWatch;
        if (w != null) w.sessionUp();
    }

    static void rgSessionEnd() {
        RgWatch w = rgWatch;
        if (w != null) w.sessionEnd();
    }

    static void rgLinkDown(String why) {
        RgWatch w = rgWatch;
        if (w != null) w.linkDown(why);
    }

    static void rgAsk(String how) {
        RgWatch w = rgWatch;
        if (w != null) w.ask(how);
        else askAgain();
    }

    /** DiPO's iAP2 connection (cached Field lookup on DiPO's manager class, fallback to HMG-API's). */
    private static IIap2Service dipoIap2(Object manager) {
        if (manager != null) {
            Class<?> owner = manager.getClass();
            Field cached = sDipoIap2Field;
            if (cached == null || sDipoIap2Owner != owner) {
                cached = null;
                for (Class<?> c = owner; c != null && c != Object.class && cached == null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType() == IIap2Service.class) {
                            f.setAccessible(true);
                            cached = f;
                            break;
                        }
                    }
                }
                sDipoIap2Field = cached;
                sDipoIap2Owner = owner;
            }
            if (cached != null) {
                try {
                    Object s;
                    synchronized (manager) {
                        s = cached.get(manager);
                    }
                    if (s != null) return (IIap2Service) s;
                } catch (Throwable t) {
                    error("Now Playing: DiPO's iAP2 connection", t);
                }
            }
        }
        return iap2;
    }

    private static void reconnectFix(final ClassLoader cl) {
        mod.hook(cl, "dipo.session_listener.session_details", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                SESSION.set(p.args[0] == null ? null : (String) p.args[0]);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                SESSION.remove();
                NO_ADDRESS.remove();
            }
        });
        mod.hook(cl, "dipo.iap2.bt_address", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                String id = SESSION.get();
                if (id == null || p.getResult() != null) return;
                p.setResult(id);
                if (id.isEmpty()) NO_ADDRESS.set(Boolean.TRUE);
                log("bridge: no iAP2 Bluetooth address on session details, using the session's (" + (id.isEmpty() ? "none, keeping the stored one" : id) + ")");
            }
        });
        mod.hook(cl, "dipo.bt.set_carplay_bt_address", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (NO_ADDRESS.get() != null) p.setResult(null);
            }
        });
    }

    private static void firstConnectNotice() {
        app.context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String id = Bridge.sLastBtId;
                if (id == null) return;
                try {
                    SharedPreferences sp = c.getSharedPreferences("pref_dipo", 0);
                    if (!sp.edit().putBoolean(id, true).commit()) log("bridge: first-connect notice not saved for " + id);
                } catch (Throwable t) {
                    error("bridge: first-connect notice", t);
                }
            }
        }, new IntentFilter(CP_FIRST_CONNECTED));
    }
}
