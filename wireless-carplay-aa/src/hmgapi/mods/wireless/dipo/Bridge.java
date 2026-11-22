package hmgapi.mods.wireless.dipo;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.lang.reflect.Method;
import java.util.ArrayList;

import hmgapi.hook.Points;
import hmgapi.hook.Wifi;

/**
 * Wireless CarPlay's Bluetooth bridge, in DiPO (from the bridge build, com.ev6.cpwl).
 */
final class Bridge {
    static final String ACTION_ATTACH = "com.ev6.cpwl.BT_ATTACHED", ACTION_DETACH = "com.ev6.cpwl.BT_DETACHED";
    static final String IAP2_PKG = "com.tcc.iap2server";
    static final int APPLE_VENDOR_ID = 76;
    static final String UUID_APPLE_IAP = "00000000-deca-fade-deca-deafdecacaff";
    static final String UUID_CARPLAY_WIRELESS = "2d8d2466-e14d-451c-88bc-7301abea291a";

    static final int COMPONENT_USB = 1, COMPONENT_BT_CARPLAY = 4, COMPONENT_CARPLAY_CLIENT = 5;
    static final int LISTENER_EXTERNAL_BT_TRANSPORT = 1024, LISTENER_EXTERNAL_CARPLAY_TRANSPORT = 2048;
    static final int NOTI_IDENTIFICATION_ACCEPTED = 0, NOTI_TRANSPORT_IDENTIFIER = 24;
    static final int NOTI_BT_TRANSPORT_DATA = 80, NOTI_CARPLAY_CLIENT_TRANSPORT_DATA = 81, OBJ_BLOB = 11;
    static final int MAX_PENDING = 64;
    static final long SPP_CONNECT_DELAY_MS = 3000;
    static final int SPP_DISCONNECTED = 0, SPP_CONNECTED = 2, EVENT_BTCM_CONNECTED = 2;
    static final String ACTION_IAP2_CONNECTFAIL = "com.tcc.iap2.connectedfail", EXTRA_IAP2_COMPONENT = "com.telechips.android.iap2.component";
    static final String ACTION_IAP2_CONNECTSUCCESS = "com.tcc.iap2.connectedsuccess";
    static final long SEND_SLOW_MS = 2000;
    static final long STUCK_MS = 5000;

    static volatile String sAddr;
    static volatile HfDeviceBTCMManager sBtcm;
    static volatile Object sCp;
    static volatile boolean sCpClient, sCpConnected, sCpWireless, sSession, sUsb;
    static volatile IIap2Service sIap2;
    static volatile String sLastBtId;
    static volatile String sCpSerial;
    static final Reattach sReattach = new Reattach(new ReattachHost());
    static ClassLoader cl;
    static Context context;
    private static Handler sHandler;
    private static Method sNativeSend;
    private static volatile Method sPropGet;
    private static volatile boolean sPropResolved;
    private static volatile Method sGetVendor;
    private static volatile boolean sVendorResolved;

    /** Guards strict FIFO delivery of buffered + live inbound CarPlay-channel iAP2 packets. */
    private static final Object sRxLock = new Object();
    private static final ArrayList<byte[]> sPendingIn = new ArrayList<byte[]>();
    private static boolean sFlushing;

    private static BtListener sBt;
    private static TxListener sTx;
    static final CpTx sCpTx = new CpTx(new TxHost(), new NativeSink(), "hmgapi-cp-tx");
    private static volatile long sTxReported, sTxStuck;
    private static boolean sTxWatch;

    private Bridge() {}

    static void log(String m) {
        Dipo.log("bridge: " + m);
    }

    static void error(String m, Throwable t) {
        Dipo.error("bridge: " + m, t);
    }

    static void onBtcmReady(HfDeviceBTCMManager m) {
        if (m == null) return;
        try {
            sBtcm = m;
            synchronized (Bridge.class) {
                if (sBt == null) sBt = new BtListener();
            }
            m.registerHfDeviceStatusListener(sBt);
            log("BTCM listener registered");
            BluetoothDevice d = m.IbtcmGetHfpConnectedDevice();
            if (d != null && isApple(d)) scheduleSppConnect(d.getAddress());
        } catch (Throwable t) {
            error("onBtcmReady", t);
        }
    }

    static boolean isOurs(Object listener) {
        return listener != null && listener == sBt;
    }

    static void onIap2Bound(IIap2Service s) {
        if (s == null) return;
        try {
            sIap2 = s;
            synchronized (Bridge.class) {
                if (sTx == null) sTx = new TxListener();
            }
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_EXTERNAL_BT_TRANSPORT | LISTENER_EXTERNAL_CARPLAY_TRANSPORT);
            info.onSetListenerName("EV6_CPWL_BT");
            log("Iap2SvcInit(EV6_CPWL_BT, 0xc00) = " + s.Iap2SvcInit(sTx, info));
        } catch (Throwable t) {
            error("onIap2Bound", t);
        }
    }

    /** Clears wedged transport state if com.tcc.iap2server crashes or restarts. */
    static void onIap2ServiceLost() {
        sIap2 = null;
        synchronized (sPendingIn) {
            sCpConnected = false;
            sFlushing = false;
            sPendingIn.clear();
        }
        sSession = false;
        sUsb = false;
        if (sCpClient) {
            Dipo.rgLinkDown("iAP2 service disconnected");
            if (sReattach.active() && !sReattach.busy()) sReattach.lost("iAP2 service disconnected");
        }
    }

    static boolean enabled() {
        return !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }

    static String prop(String key, String fallback) {
        try {
            Method m = sPropGet;
            if (m == null && !sPropResolved) {
                try {
                    m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
                    sPropGet = m;
                } finally {
                    sPropResolved = true;
                }
            }
            return m == null ? fallback : (String) m.invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("hmgapi-cpwl");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    static boolean isApple(BluetoothDevice d) {
        if (d == null) return false;
        try {
            Method gv = sGetVendor;
            if (gv == null && !sVendorResolved) {
                try {
                    gv = BluetoothDevice.class.getMethod("getVendor");
                    sGetVendor = gv;
                } catch (Throwable ignored) {
                } finally {
                    sVendorResolved = true;
                }
            }
            if (gv != null) {
                Object v = gv.invoke(d);
                if (v instanceof Integer && (Integer) v == APPLE_VENDOR_ID) return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    if (u == null || u.getUuid() == null) continue;
                    String s = u.getUuid().toString();
                    if (UUID_APPLE_IAP.equalsIgnoreCase(s) || UUID_CARPLAY_WIRELESS.equalsIgnoreCase(s)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String n = d.getName();
            return n != null && (n.contains("iPhone") || n.contains("iPad"));
        } catch (Throwable t) {
            return false;
        }
    }

    static void scheduleSppConnect(final String addr) {
        if (addr == null || !enabled() || sUsb || sSession || sCpWireless) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m == null || sUsb || sSession || sCpWireless || !enabled()) return;
                    if (m.IbtcmIsSppConnectedDevice(addr)) log("SPP already up for " + addr);
                    else log("IbtcmSppConnect(" + addr + ") = " + m.IbtcmSppConnect(addr));
                } catch (Throwable t) {
                    error("sppConnect", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onSppUp(final String addr) {
        if (addr == null) return;
        synchronized (Bridge.class) {
            if (sSession) return;
            sAddr = addr;
            if (sCpClient || sCpWireless || !enabled() || sUsb) return;
            sSession = true;
        }
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) {
                        log("no context, cannot start session");
                        sSession = false;
                        return;
                    }
                    Wifi.projectionAp(c, "wireless-carplay-aa");
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_BT_CARPLAY);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("BT_ATTACHED sent for " + addr);
                } catch (Throwable t) {
                    sSession = false;
                    error("onSppUp", t);
                }
            }
        });
    }

    static void onUsbGone() {
        final String addr = sAddr;
        if (addr == null || sSession) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr);
                } catch (Throwable t) {
                    error("onUsbGone", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onCpSessionCreated(Object cp) {
        if (cp != null) sCp = cp;
        log("CarPlay session created (usb=" + sUsb + " btAddr=" + sAddr + ")");
        if ("created".equals(prop("persist.ev6.cpwl.handover", "data")) && !sUsb && sAddr != null) {
            startCpClient("session created");
        }
    }

    static void onCpSessionStarted(int transport) {
        sCpWireless = transport == 1 && !sUsb;
        log("CarPlay session started transport=" + transport + " wireless=" + sCpWireless);
    }

    /**
     * Inbound iAP2 bytes from the CarPlay channel: strictly ordered so a packet arriving while
     * flushPending drains sPendingIn never overtakes buffered handshake packets.
     */
    static void onCpIapIn(Object cp, byte[] data, int len) {
        try {
            if (cp != null) sCp = cp;
            if (data == null || len <= 0 || sUsb) return;
            if (len > data.length) len = data.length;
            byte[] b = new byte[len];
            System.arraycopy(data, 0, b, 0, len);
            if (!sCpClient) startCpClient("iAP2 data from CarPlay channel");
            IIap2Service s = sIap2;
            synchronized (sPendingIn) {
                if (!sCpConnected || sFlushing || s == null || !sPendingIn.isEmpty()) {
                    if (sPendingIn.size() < MAX_PENDING) sPendingIn.add(b);
                    return;
                }
            }
            synchronized (sRxLock) {
                IIap2Service cur = sIap2;
                if (sCpConnected && cur != null) {
                    cur.Iap2SvcTransportReadDone(b, len);
                } else {
                    synchronized (sPendingIn) {
                        if (sPendingIn.size() < MAX_PENDING) sPendingIn.add(b);
                    }
                }
            }
        } catch (Throwable t) {
            error("onCpIapIn", t);
        }
    }

    static void onCpSessionFinalized(int type) {
        try {
            log("CarPlay session finalized type=" + type + " cpClient=" + sCpClient);
            sReattach.sessionEnd();
            Dipo.rgSessionEnd();
            boolean client, wireless;
            synchronized (Bridge.class) {
                client = sCpClient;
                wireless = sCpWireless;
                if (client) sCpTx.reset("CarPlay session ended", false);
                sCpWireless = false;
                sCpClient = false;
            }
            synchronized (sPendingIn) {
                sCpConnected = false;
                sFlushing = false;
                sPendingIn.clear();
            }
            if (client) sendDetach();
            if (client || wireless) onUsbGone();
        } catch (Throwable t) {
            error("onCpSessionFinalized", t);
        }
    }

    static boolean dipoIgnoreDisconnect(int component) {
        boolean ignore = (sCpClient || sCpWireless) && (component == COMPONENT_BT_CARPLAY || component == COMPONENT_CARPLAY_CLIENT);
        if (ignore) log("DiPO: ignoring iAP2 disconnect comp=" + component + " (wireless CarPlay up)");
        return ignore;
    }

    static boolean dipoDropNoti(int what) {
        if (what == NOTI_IDENTIFICATION_ACCEPTED && sCpClient && !sUsb) {
            comp5Ready("identification accepted", sCpConnected);
        }
        boolean drop = sCpClient && (what == NOTI_IDENTIFICATION_ACCEPTED || what == NOTI_TRANSPORT_IDENTIFIER);
        if (drop) log("DiPO: dropping iAP2 noti " + what + " from the CarPlay-channel session");
        return drop;
    }

    static void startCpClient(final String why) {
        final String addr;
        synchronized (Bridge.class) {
            if (sCpClient) return;
            sCpClient = true;
            addr = sAddr;
            sCpSerial = addr;
        }
        synchronized (sPendingIn) {
            sCpConnected = false;
            sFlushing = false;
        }
        if (reattachEnabled()) sReattach.sessionUp();
        Dipo.rgSessionUp();
        txWatch();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested (" + why + ")");
                } catch (Throwable t) {
                    error("startCpClient", t);
                }
            }
        });
    }

    static void onCpClientConnected() {
        IIap2Service s = sIap2;
        if (s == null) {
            synchronized (sPendingIn) {
                sCpConnected = true;
            }
            return;
        }
        flushPending(s);
    }

    private static void flushPending(IIap2Service s) {
        synchronized (sRxLock) {
            synchronized (sPendingIn) {
                if (sPendingIn.isEmpty()) {
                    sFlushing = false;
                    sCpConnected = true;
                    return;
                }
                sFlushing = true;
            }
            int total = 0;
            while (true) {
                ArrayList<byte[]> batch;
                synchronized (sPendingIn) {
                    if (sPendingIn.isEmpty()) {
                        sFlushing = false;
                        sCpConnected = true;
                        break;
                    }
                    batch = new ArrayList<byte[]>(sPendingIn);
                    sPendingIn.clear();
                }
                for (byte[] b : batch) {
                    try {
                        s.Iap2SvcTransportReadDone(b, b.length);
                        total++;
                    } catch (Throwable t) {
                        error("flushPending", t);
                    }
                }
            }
            if (total > 0) log("flushed " + total + " buffered CarPlay-channel packets");
        }
    }

    private static Method nativeSend() {
        if (sNativeSend == null) sNativeSend = Points.method(cl, "dipo.carplay.native_iap_send");
        return sNativeSend;
    }

    static void writeCp(byte[] data) {
        final Object cp = sCp;
        if (cp == null || data == null || data.length == 0 || !sCpClient) return;
        sCpTx.send(cp, data.clone());
    }

    static final class NativeSink implements CpTx.Sink {
        @Override
        public int send(Object cp, byte[] b, int len) throws Throwable {
            Object r = nativeSend().invoke(cp, b, len);
            return r instanceof Integer ? (Integer) r : -1;
        }
    }

    static final class TxHost implements CpTx.Host {
        @Override
        public long now() {
            return Bridge.now();
        }

        @Override
        public int tid() {
            return android.os.Process.myTid();
        }

        @Override
        public void log(String m) {
            Bridge.log(m);
        }
    }

    static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }

    static long stuckMs() {
        try {
            return Long.parseLong(prop("persist.ev6.cpwl.stuck_ms", String.valueOf(STUCK_MS)).trim());
        } catch (Throwable t) {
            return STUCK_MS;
        }
    }

    private static void txWatch() {
        synchronized (Bridge.class) {
            if (sTxWatch) return;
            sTxWatch = true;
        }
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    long since = sCpTx.blockedSince();
                    long ms = since == 0 ? 0 : now() - since;
                    if (since != 0 && since != sTxReported && ms > SEND_SLOW_MS) {
                        sTxReported = since;
                        log("CarPlay-channel iAP2 send blocked for " + ms + " ms so far (" + sCpTx.describe() + ")");
                    }
                    long stuck = stuckMs();
                    if (since != 0 && since != sTxStuck && stuck > 0 && ms > stuck && sCpClient && !sUsb && sReattach.active()) {
                        sTxStuck = since;
                        log("CarPlay-channel iAP2 send blocked " + ms + " ms: the link counts as lost (tx thread " + sCpTx.threadNumber() + ")");
                        Dipo.rgLinkDown("send blocked");
                        sReattach.lost("CarPlay-channel send blocked " + ms + " ms");
                    }
                } catch (Throwable t) {
                    error("txWatch", t);
                }
                if (sCpClient) {
                    handler().postDelayed(this, 1000);
                } else {
                    synchronized (Bridge.class) {
                        sTxWatch = false;
                    }
                }
            }
        }, 1000);
    }

    static boolean reattachEnabled() {
        return !"0".equals(prop("persist.ev6.cpwl.reattach", "1"));
    }

    static void onIap2ConnectFail(int component) {
        try {
            if (component == COMPONENT_CARPLAY_CLIENT && sCpClient && !sUsb) Dipo.rgLinkDown("ACTION_IAP2_CONNECTFAIL");
            if (component != COMPONENT_CARPLAY_CLIENT || !sCpClient || sUsb || !sReattach.active()) {
                log("iAP2 connect fail comp=" + component + " (cpClient=" + sCpClient + " usb=" + sUsb + "): not the CarPlay channel's, left alone");
                return;
            }
            sReattach.lost("ACTION_IAP2_CONNECTFAIL / LimitMaxResend");
        } catch (Throwable t) {
            error("onIap2ConnectFail", t);
        }
    }

    static void onIap2ConnectSuccess(int component) {
        try {
            log("iAP2 connect success comp=" + component);
            if (component == COMPONENT_CARPLAY_CLIENT && sCpClient && !sUsb) comp5Ready("ACTION_IAP2_CONNECTSUCCESS", true);
        } catch (Throwable t) {
            error("onIap2ConnectSuccess", t);
        }
    }

    static void comp5Ready(String how, boolean linkUp) {
        RgWatch.ready(sReattach, Dipo.rgWatch, how, linkUp);
    }

    static void reattachCpClient(final int attempt) {
        if (!sCpClient || sUsb) {
            log("re-attach #" + attempt + " skipped (cpClient=" + sCpClient + " usb=" + sUsb + ")");
            return;
        }
        Dipo.rgLinkDown("re-attach #" + attempt);
        synchronized (sPendingIn) {
            sCpConnected = false;
            sFlushing = false;
            sPendingIn.clear();
        }
        sCpTx.reset("re-attach #" + attempt, true);
        final String addr = sCpSerial != null ? sCpSerial : sAddr;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested again (re-attach #" + attempt + ")");
                } catch (Throwable t) {
                    error("reattachCpClient", t);
                }
            }
        });
    }

    static final class ReattachHost implements Reattach.Host {
        @Override
        public long now() {
            return Bridge.now();
        }

        @Override
        public Object schedule(Runnable r, long delayMs) {
            handler().postDelayed(r, delayMs);
            return r;
        }

        @Override
        public void cancel(Object token) {
            handler().removeCallbacks((Runnable) token);
        }

        @Override
        public void handover(int attempt) {
            reattachCpClient(attempt);
        }

        @Override
        public void askAgain() {
            Dipo.rgAsk("re-attach");
        }

        @Override
        public void log(String m) {
            Bridge.log(m);
        }
    }

    static void onSppDown(String addr) {
        if (addr == null || !addr.equals(sAddr)) return;
        if (sCpWireless) {
            sSession = false;
            startCpClient("bluetooth dropped during wireless CarPlay");
            return;
        }
        sAddr = null;
        if (sSession) {
            sSession = false;
            sendDetach();
        }
    }

    static void sendDetach() {
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_DETACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    c.sendBroadcast(i);
                    log("BT_DETACHED sent");
                } catch (Throwable t) {
                    error("sendDetach", t);
                }
            }
        });
    }

    static void onSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            String addr = sAddr;
            IIap2Service s = sIap2;
            if (addr == null || s == null || d == null || data == null || len <= 0 || !sSession || !addr.equals(d.getAddress())) return;
            if (len > data.length) len = data.length;
            if (len != data.length) {
                byte[] b = new byte[len];
                System.arraycopy(data, 0, b, 0, len);
                data = b;
            }
            s.Iap2SvcTransportReadDone(data, len);
        } catch (Throwable t) {
            error("onSppData", t);
        }
    }

    static void writeSpp(byte[] data) {
        try {
            String addr = sAddr;
            HfDeviceBTCMManager m = sBtcm;
            if (addr == null || m == null || data == null || data.length == 0 || !sSession) return;
            if (!m.IbtcmSppOutPutStreamWrite(addr, data)) log("SPP write failed, " + data.length + " bytes");
        } catch (Throwable t) {
            error("writeSpp", t);
        }
    }

    static final class BtListener extends HfDeviceStatusListener {
        @Override
        public void onBTCMDeviceConnectionEventChanged(BluetoothDevice d, int event, boolean[] profiles) {
            if (event != EVENT_BTCM_CONNECTED) return;
            try {
                if (isApple(d)) {
                    log("iPhone BT connected: " + d.getAddress());
                    scheduleSppConnect(d.getAddress());
                }
            } catch (Throwable t) {
                error("connEvent", t);
            }
        }

        @Override
        public void onBTCMSppStatusChanged(BluetoothDevice d, int status) {
            if (d == null) return;
            try {
                if (!isApple(d)) return;
                String addr = d.getAddress();
                log("SPP status " + status + " for " + addr);
                if (status == SPP_CONNECTED) onSppUp(addr);
                else if (status == SPP_DISCONNECTED) onSppDown(addr);
            } catch (Throwable t) {
                error("sppStatus", t);
            }
        }

        @Override
        public void onBTCMSppInPutStreamChanged(BluetoothDevice d, byte[] buffer, int length) {
            onSppData(d, buffer, length);
        }
    }

    static final class TxListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {
            log("iAP2 connected comp=" + component);
            if ((component == COMPONENT_USB || component == COMPONENT_BT_CARPLAY) && serial != null && serial.contains(":")) sLastBtId = serial;
            if (component == COMPONENT_USB) sUsb = true;
            else if (component == COMPONENT_CARPLAY_CLIENT) {
                onCpClientConnected();
                sReattach.connected();
            }
        }

        @Override
        public void disconnected(int component) {
            log("iAP2 disconnected comp=" + component);
            if (component == COMPONENT_USB) {
                sUsb = false;
                onUsbGone();
            } else if (component == COMPONENT_BT_CARPLAY) {
                sSession = false;
            } else if (component == COMPONENT_CARPLAY_CLIENT) {
                synchronized (sPendingIn) {
                    sCpConnected = false;
                    sFlushing = false;
                }
                if (sCpClient && !sUsb) Dipo.rgLinkDown("iAP2 disconnected comp=5");
                if (sCpClient && !sUsb && sReattach.active() && !sReattach.busy()) sReattach.lost("iAP2 disconnected comp=5");
            }
        }

        @Override
        public void notify(int what, int a1, int a2, Iap2NotiMsg msg) {
            if ((what != NOTI_BT_TRANSPORT_DATA && what != NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) || msg == null) return;
            try {
                Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
                if (g == null) return;
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemParamType() != OBJ_BLOB) continue;
                    if (what == NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) writeCp(it.getItemByteArray());
                    else writeSpp(it.getItemByteArray());
                }
            } catch (Throwable t) {
                error("notify", t);
            }
        }
    }
}
