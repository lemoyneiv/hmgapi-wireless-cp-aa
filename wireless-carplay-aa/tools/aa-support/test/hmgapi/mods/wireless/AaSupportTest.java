package hmgapi.mods.wireless;

import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import hmgapi.hook.Cluster;
import android.os.Handler;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;

public final class AaSupportTest {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static ModContext enabled() {
        ModContext mod = new ModContext();
        // An existing owner's saved true must not bypass native compatibility.
        mod.settings.put("tbt", true);
        mod.settings.put("tbt_standard", true);
        return mod;
    }

    private static void rejectsGpm(File library) {
        ModContext mod = enabled();
        new Wireless(library).appLoaded(mod, new LoadedApp(Wireless.GPM));
        check(mod.natives.isEmpty(), "unverified AA must not request a native patch");
        check(mod.hooks.size() == 1 && mod.hooks.containsKey("framework.context_impl.start_service"),
                "unverified AA keeps only the independent USB hook, without navigation/callback hooks");
        check(mod.logs.toString().contains("no verified recipe"), "owner gets a reason AA TBT is off");
    }

    private static void navigation(File library, int step, boolean aa, boolean cp, boolean skip) throws Throwable {
        Car.step = step;
        Car.aaGuiding = aa;
        Car.cpGuiding = cp;
        ModContext mod = enabled();
        new Wireless(library).appLoaded(mod, new LoadedApp(Wireless.NAVIGATION));
        check(mod.hooks.size() == Wireless.NAVI_TBT.length, "all navigation arbitration hooks remain");
        for (MethodHook hook : mod.hooks.values()) {
            check(hook.skipsOriginal() == skip, "navigation yields only to available phone guidance");
        }
    }

    /** Exercises the actual Wireless callback hooks, not only their declarations. */
    private static void supportedGpm(File library) throws Throwable {
        Car.step = 105;
        ModContext mod = enabled();
        new Wireless(library).appLoaded(mod, new LoadedApp(Wireless.GPM));
        check(mod.natives.equals(Arrays.asList("libautoreceiver_infobank.so")), "supported AA requests its native recipe once");
        check(mod.hooks.keySet().equals(new LinkedHashSet<String>(Arrays.asList(
                "framework.context_impl.start_service", "gpm.navigation_status.init",
                "gpm.navigation_status.next_turn", "gpm.navigation_status.next_turn_distance",
                "gpm.navigation_status.status", "gpm.receiver.send_navigation_focus", "gpm.receiver.destroy"))),
                "supported AA installs all dependent hooks and preserves USB protection");
        Object receiver = new Object(), status = new Object();
        Object receiver2 = new Object(), status2 = new Object();
        Points.receivers.put(status, receiver);
        Points.receivers.put(status2, receiver2);
        AaTurns.instances.clear();

        MethodHook init = mod.hooks.get("gpm.navigation_status.init");
        MethodHook.Param p = init.before(status, 9, 123L, 500, Points.IMAGE, 480, 480, 16);
        check(p.args[3] == Points.ENUM && p.args[0].equals(9) && p.args[1].equals(123L)
                && p.args[2].equals(500) && p.args[4].equals(480) && p.args[5].equals(480) && p.args[6].equals(16),
                "navigation init changes only IMAGE to ENUM at Java argument 3");
        Object otherClusterType = new Object();
        p = init.before(status, 9, 123L, 500, otherClusterType, 480, 480, 16);
        check(p.args[3] == otherClusterType, "non-IMAGE cluster type is preserved");

        MethodHook focus = mod.hooks.get("gpm.receiver.send_navigation_focus");
        MethodHook route = mod.hooks.get("gpm.navigation_status.status");
        MethodHook turn = mod.hooks.get("gpm.navigation_status.next_turn");
        MethodHook distance = mod.hooks.get("gpm.navigation_status.next_turn_distance");
        MethodHook destroy = mod.hooks.get("gpm.receiver.destroy");
        focus.after(receiver, Points.FOCUS_NATIVE);
        Handler.drain();
        check(AaTurns.instances.isEmpty(), "focus alone never creates a turn manager");

        Cluster.available = false;
        route.after(status, 1);
        Handler.drain();
        check(AaTurns.instances.isEmpty(), "unavailable cluster is harmless and creates no manager");
        Cluster.available = true;
        route.after(status, 1);
        Object[] turnArgs = { "Road", 2, 4, new byte[] { 9 }, 90, 7 };
        turn.after(status, turnArgs);
        turnArgs[0] = "changed after callback";
        distance.after(status, 120, 10, 120000, 1);
        check(AaTurns.instances.isEmpty(), "callbacks are posted before being dispatched");
        Handler.drain();
        check(AaTurns.instances.size() == 1, "callbacks share one manager per receiver");
        AaTurns first = AaTurns.instances.get(0);
        check(first.standardTurns, "saved standard-turn preference is copied into the manager");
        check(first.calls.equals(Arrays.asList("status 1", "turn Road 2 4 90 7", "distance 120 10 120000 1")),
                "dispatch preserves callback order, argument snapshot, field positions and distance flags");

        mod.settings.put("tbt_standard", false);
        route.after(status2, 3);
        Handler.drain();
        check(AaTurns.instances.size() == 2 && !AaTurns.instances.get(1).standardTurns,
                "a second receiver gets an independent manager and current preference");
        AaTurns second = AaTurns.instances.get(1);
        focus.after(receiver, Points.FOCUS_NATIVE + 1);
        Handler.drain();
        check(first.calls.size() == 3, "non-native navigation focus does not stop phone guidance");
        focus.after(receiver, Points.FOCUS_NATIVE);
        Handler.drain();
        check(first.calls.get(first.calls.size() - 1).equals("stop") && second.calls.equals(Arrays.asList("status 3")),
                "native navigation focus stops only the matching receiver");
        route.after(status, 2);
        destroy.before(receiver);
        destroy.after(receiver);
        Handler.drain();
        check(first.calls.subList(4, first.calls.size()).equals(Arrays.asList("status 2", "end")),
                "route end and receiver destruction dispatch once and in order");
        focus.after(receiver, Points.FOCUS_NATIVE);
        Handler.drain();
        check(first.calls.size() == 6 && AaTurns.instances.size() == 2,
                "destroy removes the receiver and later focus cannot revive it");
        route.after(status, 1);
        Handler.drain();
        check(AaTurns.instances.size() == 3 && AaTurns.instances.get(2).calls.equals(Arrays.asList("status 1")),
                "later connection callbacks get a fresh manager after destruction");

        navigation(library, 105, true, false, true);
        navigation(library, 105, false, true, true);
        navigation(library, 105, false, false, false);
        ModContext disabled = enabled();
        disabled.settings.put("tbt", false);
        new Wireless(library).appLoaded(disabled, new LoadedApp(Wireless.GPM));
        new Wireless(library).appLoaded(disabled, new LoadedApp(Wireless.NAVIGATION));
        check(disabled.natives.isEmpty() && disabled.hooks.size() == 1,
                "supported library still honors tbt=false and preserves USB hook");
        ModContext own = enabled();
        Car.step = 999;
        new Wireless(library).appLoaded(own, new LoadedApp(Wireless.GPM));
        check(own.natives.isEmpty() && own.hooks.size() == 1,
                "supported library leaves step999 stock turn manager alone");
        Car.step = 105;
        Points.receivers.clear();
        AaTurns.instances.clear();
    }

    public static void main(String[] args) throws Throwable {
        check(AaTbtSupport.supportsDigest("48ee793af8b86052503b8c952b034731"), "existing US library retained");
        check(AaTbtSupport.supportsDigest("a0bea1c4992ea983a8190c4e032d7190"), "existing Canadian library retained");
        check(AaTbtSupport.supportsDigest("dbfe7bd2e36152a50f97fed612cf6326"), "CEEV library has an exact recipe");
        check(!AaTbtSupport.supportsDigest(null), "unknown digest rejected");
        check(!AaTbtSupport.supportsDigest(""), "empty digest rejected");
        check(!AaTbtSupport.supportsDigest("48ee793af8b86052503b8c952b034730"), "different library rejected");

        File directory = Files.createTempDirectory("aa-support-").toFile();
        File unknown = new File(directory, "unknown.so");
        File missing = new File(directory, "missing.so");
        try {
            Files.write(unknown.toPath(), new byte[] { 1, 2, 3, 4 });
            check(!AaTbtSupport.supports(unknown), "unknown file rejected");
            check(!AaTbtSupport.supports(missing), "missing file rejected");
            check(!AaTbtSupport.supports(directory), "file read failure rejected");
            check(!AaTbtSupport.supports(null), "missing path rejected");
            rejectsGpm(unknown);
            rejectsGpm(missing);
            rejectsGpm(directory);
            navigation(unknown, 105, true, false, false);
            navigation(unknown, 105, false, true, true);
            navigation(unknown, 105, true, true, true);
            navigation(unknown, 105, false, false, false);
            navigation(unknown, 999, true, false, true);

            Car.step = 105;
            Wireless wireless = new Wireless(unknown);
            ModContext cp = enabled();
            wireless.appLoaded(cp, new LoadedApp(Wireless.DIPO));
            check(cp.cpStarts == 1, "unknown AA library does not block CarPlay's app part");
            ModContext iap2 = enabled();
            wireless.appLoaded(iap2, new LoadedApp(Wireless.IAP2));
            check(iap2.hooks.containsKey("iap2.init_info.check_supports_features"), "CarPlay route guidance remains enabled");
            check(iap2.hooks.containsKey("iap2.service.on_create") && iap2.hooks.containsKey("iap2.service_receiver.on_receive"),
                    "CarPlay Bluetooth bridge hooks remain enabled");
            ModContext menu = enabled();
            wireless.appLoaded(menu, new LoadedApp(Wireless.BTSETTINGS));
            check(menu.hooks.containsKey("btsettings.projection_helper.open_wireless_page"), "projection menu unaffected");
            ModContext launcher = enabled();
            wireless.appLoaded(launcher, new LoadedApp(Wireless.LAUNCHER));
            check(launcher.hooks.containsKey("framework.activity.start_activity_for_result"), "launcher wireless popup redirect installed");
            ModContext sysSettings = enabled();
            wireless.appLoaded(sysSettings, new LoadedApp(Wireless.SETTINGS_APP));
            check(sysSettings.hooks.containsKey("framework.activity.start_activity_for_result"), "android settings wireless popup redirect installed");

            ModContext off = enabled();
            off.settings.put("tbt", false);
            wireless.appLoaded(off, new LoadedApp(Wireless.GPM));
            wireless.appLoaded(off, new LoadedApp(Wireless.NAVIGATION));
            check(off.natives.isEmpty() && off.hooks.size() == 1, "tbt=false still leaves only USB hook");
            ModContext stock = enabled();
            Car.step = 999;
            wireless.appLoaded(stock, new LoadedApp(Wireless.GPM));
            check(stock.natives.isEmpty() && stock.hooks.size() == 1, "native GPM TBT is preserved at step 999");
            Car.step = 105;
            testNaviBridge(unknown);
            if (args.length > 0) {
                File ceev = new File(args[0]);
                check(ceev.isFile(), "private CEEV library exists");
                check(AaTbtSupport.supports(ceev), "private CEEV library matches the allowlist");
                supportedGpm(ceev);
            }
        } finally {
            Files.deleteIfExists(unknown.toPath());
            Files.deleteIfExists(directory.toPath());
        }
        System.out.println("ok    AA native support & NaviBridge: " + checks + " checks (no car access)");
    }

    public static final class FakeTrapInfo {
        public int barGraphLevel;
        public int speedLimit;
        public int speedUnit;

        public FakeTrapInfo() {}

        FakeTrapInfo(int bar, int limit, int unit) {
            this.barGraphLevel = bar;
            this.speedLimit = limit;
            this.speedUnit = unit;
        }
    }

    public static final class FakeClusterTarget {
        final java.util.List<String> replayed = new java.util.ArrayList<String>();

        public void updateTbtInfo(int type, int sides, float dir) {
            replayed.add("tbt " + type + " " + sides + " " + dir);
        }

        public void updateSubInfo(float dist, int flag, int unit, int h, int m, int tt, int tf, float heading, int a, int b) {
            replayed.add("sub " + flag + " " + heading);
        }

        public void updateTrapInt(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7,
                                  int a8, int a9, int a10, int bar, int speedLimit, int a13, int a14, int a15, int a16) {
            replayed.add("trapInt bar=" + bar + " speed=" + speedLimit);
        }

        public void updateTrapObj(Object info) {
            FakeTrapInfo f = (FakeTrapInfo) info;
            replayed.add("trapObj bar=" + f.barGraphLevel + " speed=" + f.speedLimit + " unit=" + f.speedUnit);
        }
    }

    private static void testNaviBridge(File unknownLib) throws Throwable {
        Points.availablePoints.clear();
        Points.classes.clear();
        Points.availablePoints.add(NaviBridge.PT_TRAP_INT);
        Points.availablePoints.add(NaviBridge.PT_TRAP_NEW);
        Points.availablePoints.add(NaviBridge.PT_TRAP_CLS);
        Points.availablePoints.add(NaviBridge.PT_TRAP_CTOR);
        Points.availablePoints.add(NaviBridge.PT_TRAP_BAR);
        Points.classes.put(NaviBridge.PT_TRAP_CLS, FakeTrapInfo.class);

        Car.aaGuiding = false;
        Car.cpGuiding = false;
        NaviBridge.testNow = 1000L;

        ModContext mod = enabled();
        NaviBridge bridge = NaviBridge.install(mod, new LoadedApp(Wireless.NAVIGATION), true, Wireless.NAVI_TBT);
        check(mod.hooks.containsKey(NaviBridge.PT_TRAP_INT) && mod.hooks.containsKey(NaviBridge.PT_TRAP_NEW),
                "NaviBridge installs both 17-int and NaviTbtTrapInfo hooks when points exist");

        FakeClusterTarget target = new FakeClusterTarget();
        java.lang.reflect.Method mTbt = FakeClusterTarget.class.getMethod("updateTbtInfo", int.class, int.class, float.class);
        java.lang.reflect.Method mSub = FakeClusterTarget.class.getMethod("updateSubInfo",
                float.class, int.class, int.class, int.class, int.class, int.class, int.class, float.class, int.class, int.class);
        java.lang.reflect.Method mTrapInt = FakeClusterTarget.class.getMethod("updateTrapInt",
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        java.lang.reflect.Method mTrapObj = FakeClusterTarget.class.getMethod("updateTrapObj", Object.class);

        MethodHook hTbt = mod.hooks.get("framework.navigation_cluster.update_tbt_info");
        MethodHook hSub = mod.hooks.get("framework.navigation_cluster.update_tbt_sub_info");
        MethodHook hTrapInt = mod.hooks.get(NaviBridge.PT_TRAP_INT);
        MethodHook hTrapObj = mod.hooks.get(NaviBridge.PT_TRAP_NEW);

        // 1. Idle stock navigation emits DisplayType 8 compass + 65 mph trap frames: passed through & captured
        MethodHook.Param p1 = hTbt.beforeMethod(mTbt, target, 8, 0, 225f);
        MethodHook.Param p2 = hSub.beforeMethod(mSub, target, 0f, -3, 15, -1, -1, -1, -1, 225f, 0, -1);
        check(!hTbt.skipsOriginal(), "idle stock compass passes through");

        Object[] trap17 = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 65, 0, 0, 0, 0 };
        hTrapInt.beforeMethod(mTrapInt, target, trap17);
        check(trap17[11].equals(0) && trap17[12].equals(65), "idle 17-int trap frame untouched");

        FakeTrapInfo stockObj = new FakeTrapInfo(0, 65, 2);
        Object[] trapObjArgs = { stockObj };
        hTrapObj.beforeMethod(mTrapObj, target, trapObjArgs);
        check(((FakeTrapInfo) trapObjArgs[0]).barGraphLevel == 0 && ((FakeTrapInfo) trapObjArgs[0]).speedLimit == 65,
                "idle NaviTbtTrapInfo untouched");

        // 2. Phone TBT starts with bar=80: stock TBT suppressed, trap frames re-sent with bar=80 & speed=65 intact
        NaviBridge.testNow = 2000L;
        bridge.onPhoneTbt("aa", true, 80, 2000L);
        Handler.drain();
        check(bridge.shouldSuppress(2000L), "stock TBT suppressed during active AA lease");
        check(target.replayed.equals(Arrays.asList("trapInt bar=80 speed=65", "trapObj bar=80 speed=65 unit=2")),
                "NaviBridge splices bar=80 into re-sent trap frames while preserving 65 mph speed limit");
        check(stockObj.barGraphLevel == 0, "original stock NaviTbtTrapInfo instance is never mutated");
        target.replayed.clear();

        // 3. Stock navigation updates speed limit to 55 mph during phone guidance: outgoing gets bar=80, baseline saves bar=0/speed=55
        NaviBridge.testNow = 2500L;
        Object[] trap55 = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 55, 0, 0, 0, 0 };
        hTrapInt.beforeMethod(mTrapInt, target, trap55);
        check(trap55[11].equals(80) && trap55[12].equals(55), "live stock 55 mph update carries spliced bar=80");
        FakeTrapInfo stock55 = new FakeTrapInfo(0, 55, 2);
        Object[] obj55Args = { stock55 };
        hTrapObj.beforeMethod(mTrapObj, target, obj55Args);
        check(((FakeTrapInfo) obj55Args[0]).barGraphLevel == 80 && ((FakeTrapInfo) obj55Args[0]).speedLimit == 55
                && stock55.barGraphLevel == 0,
                "live stock NaviTbtTrapInfo 55 mph update cloned with bar=80 without mutating stock instance");

        // 4. Phone TBT ends while Car.aaGuiding is STILL lagging true: END_GRACE_MS allows immediate compass replay & bar=0 restore!
        Car.aaGuiding = true;
        NaviBridge.testNow = 3000L;
        bridge.onPhoneTbt("aa", false, -1, 3000L);
        Handler.drain();
        check(!bridge.shouldSuppress(3200L), "END_GRACE_MS prevents lagging Car.aaGuiding from blocking compass recovery");
        check(target.replayed.contains("tbt 8 0 225.0") && target.replayed.contains("sub -3 225.0"),
                "stock DisplayType 8 compass and heading sub-info replayed immediately on route end");
        check(target.replayed.contains("trapInt bar=0 speed=55") && target.replayed.contains("trapObj bar=0 speed=55 unit=2"),
                "trap bar restored to 0 with latest 55 mph speed limit preserved");

        // 5. Lease expiry fail-safe if phone disconnects abruptly without active=false broadcast
        Car.aaGuiding = false;
        target.replayed.clear();
        NaviBridge.testNow = 10000L;
        bridge.onPhoneTbt("cp", true, 50, 10000L);
        target.replayed.clear();
        NaviBridge.testNow = 17000L; // > 6000 ms LEASE_MS
        bridge.onTick(17000L);
        check(target.replayed.contains("tbt 8 0 225.0") && target.replayed.contains("trapInt bar=0 speed=55"),
                "lease expiry fail-safe restores stock compass and clears trap bar");

        NaviBridge.testNow = -1;
        Points.availablePoints.clear();
        Points.classes.clear();
    }
}
