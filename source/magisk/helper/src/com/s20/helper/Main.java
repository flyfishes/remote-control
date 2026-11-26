package com.s20.helper;

import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/**
 * 以 uid 1000 (system) 运行的热点配置 helper。
 * 方案: TetheringManager 启动/停止 + SoftApConfiguration；配置持久化走 SemWifiManager。
 * 用法:
 *   get                                - 读当前状态与持久化配置
 *   stop                               - TetheringManager.stopTethering(WIFI)
 *   start [ssid pass band]             - 有参数则先持久化，再 TetheringRequest(SoftApConfiguration) 启动
 *                                        无参数时读取持久化配置显式传入
 *   config <ssid> <pass> <band>        - 仅持久化配置 (不启动)
 * band: 2g / 5g
 */
public class Main {

    private static Context getSystemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
    }

    private static Object buildConfig(String ssid, String pass, String band) throws Exception {
        Class<?> bClass = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object b = bClass.getDeclaredConstructor().newInstance();
        Method[] ms = bClass.getMethods();
        if (!ssid.isEmpty()) {
            for (Method m : ms) if (m.getName().equals("setSsid")) { m.invoke(b, ssid); break; }
        }
        if (!pass.isEmpty()) {
            boolean ok = false;
            for (Method m : ms) {
                if (m.getName().equals("setWpa2Passphrase") && m.getParameterCount() == 1) {
                    m.invoke(b, pass); ok = true; break;
                }
            }
            if (!ok) {
                int secType = 1; // SECURITY_TYPE_WPA2_PSK fallback
                try {
                    Field f = SoftApConfiguration.class.getField("SECURITY_TYPE_WPA2_PSK");
                    secType = f.getInt(null);
                } catch (Throwable ignored) {}
                for (Method m : ms) {
                    if (m.getName().equals("setPassphrase") && m.getParameterCount() == 2) {
                        m.invoke(b, pass, secType); ok = true; break;
                    }
                }
            }
            if (!ok) System.out.println("WARN: no passphrase setter found");
        }
        if (!band.isEmpty()) {
            int bandVal = band.toLowerCase().startsWith("5") ? 2 : 1; // BAND_2GHZ=1 BAND_5GHZ=2
            boolean ok = false;
            for (Method m : ms) {
                if (m.getName().equals("setBand") && m.getParameterCount() == 1) {
                    m.invoke(b, bandVal); ok = true; break;
                }
            }
            if (!ok) System.out.println("WARN: no setBand found");
        }
        return bClass.getMethod("build").invoke(b);
    }

    private static Object getTetheringManager(Context ctx) throws Exception {
        Object tm = ctx.getSystemService("tethering");
        if (tm == null) throw new IllegalStateException("tethering service null");
        return tm;
    }

    /** TetheringManager.stopTethering(TETHERING_WIFI)；失败回退 SemWifiManager.setWifiApEnabled(false) */
    private static void tmStop(Context ctx, Object semWm) throws Exception {
        try {
            Object tm = getTetheringManager(ctx);
            Method stop = tm.getClass().getMethod("stopTethering", int.class);
            stop.invoke(tm, 0);
            System.out.println("tmStop ok");
        } catch (Throwable t) {
            System.out.println("tmStop fallback: " + t);
            Method setEnabled = semWm.getClass().getMethod("setWifiApEnabled", SoftApConfiguration.class, boolean.class);
            System.out.println("semOff = " + setEnabled.invoke(semWm, null, false));
        }
    }

    /** TetheringManager.startTethering(TetheringRequest(SoftApConfiguration))；失败回退 SemWifiManager.setWifiApEnabled */
    private static void tmStart(Context ctx, Object semWm, Object cfg) throws Exception {
        Object tm = getTetheringManager(ctx);
        Class<?> builderClass = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Object builder = builderClass.getConstructor(int.class).newInstance(0);
        boolean applied = false;
        for (Method m : builderClass.getDeclaredMethods()) {
            if (!m.getName().equals("setSoftApConfiguration") && !m.getName().equals("addSoftApConfiguration")) continue;
            if (m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isInstance(cfg)) continue;
            m.invoke(builder, cfg);
            applied = true;
            System.out.println("request config applied via " + m.getName() + "(" + m.getParameterTypes()[0].getSimpleName() + ")");
            break;
        }
        if (!applied) System.out.println("WARN: config not applied to request, starting with persisted cfg");
        Object request = builderClass.getMethod("build").invoke(builder);
        Class<?> cbClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        Object cb = Proxy.newProxyInstance(cbClass.getClassLoader(), new Class[]{cbClass},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        System.out.println("cb: " + m.getName());
                        return null;
                    }
                });
        Executor exec = new Executor() {
            @Override public void execute(Runnable r) { r.run(); }
        };
        Method start = null;
        for (Method m : tm.getClass().getMethods()) {
            if (!m.getName().equals("startTethering")) continue;
            if (m.getParameterCount() >= 3 && m.getParameterTypes()[0] == request.getClass()) { start = m; break; }
        }
        if (start == null) throw new NoSuchMethodException("startTethering(TetheringRequest,...) not found");
        Object[] argv = new Object[start.getParameterCount()];
        argv[0] = request;
        argv[1] = exec;
        argv[2] = cb;
        start.invoke(tm, argv);
        System.out.println("tmStart invoked");
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        String ssid = args.length > 1 ? args[1] : "";
        String pass = args.length > 2 ? args[2] : "";
        String band = args.length > 3 ? args[3] : "";
        try {
            android.os.Looper.prepareMainLooper();
            Context ctx = getSystemContext();
            Object semWm = ctx.getSystemService("sem_wifi");
            Class<?> sem = semWm.getClass();
            Method setEnabled = sem.getMethod("setWifiApEnabled", SoftApConfiguration.class, boolean.class);
            Method setCfg = sem.getMethod("setSoftApConfiguration", SoftApConfiguration.class);
            Method getCfg = sem.getMethod("getSoftApConfiguration");
            Method isOn = sem.getMethod("isWifiApEnabled");

            if (mode.equals("get")) {
                System.out.println("isOn=" + isOn.invoke(semWm));
                Object cfg = getCfg.invoke(semWm);
                System.out.println("cfg=" + cfg);
                // 输出可解析的明文密码/SSID 字段
                try {
                    Method getPassphrase = cfg.getClass().getMethod("getPassphrase");
                    Object pw = getPassphrase.invoke(cfg);
                    System.out.println("PASSWORD=" + (pw == null ? "" : pw));
                } catch (Throwable t) {
                    System.out.println("PASSWORD=");
                }
                try {
                    Method getSsid = cfg.getClass().getMethod("getSsid");
                    System.out.println("SSID=" + getSsid.invoke(cfg));
                } catch (Throwable ignored) {}
                try {
                    Method getBand = cfg.getClass().getMethod("getBand");
                    int bandVal = (Integer) getBand.invoke(cfg);
                    // BAND_2GHZ=1 BAND_5GHZ=2 BAND_6GHZ=3 BAND_2_4G_5G=4
                    System.out.println("BAND=" + (bandVal == 1 ? "2g" : bandVal == 2 ? "5g" : bandVal == 3 ? "6g" : "2g+5g"));
                } catch (Throwable ignored) {}
            } else if (mode.equals("stop")) {
                tmStop(ctx, semWm);
                Thread.sleep(1500);
                System.out.println("finalIsOn=" + isOn.invoke(semWm));
                System.out.println("RESULT=" + !Boolean.TRUE.equals(isOn.invoke(semWm)));
            } else if (mode.equals("start")) {
                Object cfg;
                if (!ssid.isEmpty() || !pass.isEmpty() || !band.isEmpty()) {
                    cfg = buildConfig(ssid, pass, band);
                    // 先持久化，保证 Settings UI 与实际广播一致
                    try {
                        setCfg.invoke(semWm, cfg);
                        System.out.println("persisted: " + getCfg.invoke(semWm));
                    } catch (Throwable t) {
                        System.out.println("WARN persist failed: " + t);
                    }
                } else {
                    // 读取持久化配置，显式传入（三星对 null 会用默认值）
                    cfg = getCfg.invoke(semWm);
                    String dump = String.valueOf(cfg);
                    System.out.println("usingPersisted: " + dump.substring(0, Math.min(60, dump.length())));
                }
                if (Boolean.TRUE.equals(isOn.invoke(semWm))) {
                    System.out.println("hotspot already on, stop first");
                    tmStop(ctx, semWm);
                    Thread.sleep(2000);
                }
                try {
                    tmStart(ctx, semWm, cfg);
                    Thread.sleep(3000);
                    System.out.println("finalIsOn=" + isOn.invoke(semWm));
                } catch (Throwable t) {
                    System.out.println("tmStart fallback: " + t);
                    Object r = setEnabled.invoke(semWm, cfg, true);
                    System.out.println("semStart = " + r);
                    Thread.sleep(3000);
                    System.out.println("finalIsOn=" + isOn.invoke(semWm));
                }
                System.out.println("RESULT=" + Boolean.TRUE.equals(isOn.invoke(semWm)));
            } else if (mode.equals("config")) {
                Object cfg = buildConfig(ssid, pass, band);
                setCfg.invoke(semWm, cfg);
                System.out.println("setSoftApConfiguration done");
                System.out.println("readback=" + getCfg.invoke(semWm));
            } else if (mode.equals("probe")) {
                for (Method m : sem.getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("softapconfig") || n.contains("wifiapenabled") || n.contains("band"))
                        System.out.println("sem: " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                }
                Object tm = getTetheringManager(ctx);
                System.out.println("tmClass = " + tm.getClass().getName());
                for (Method m : tm.getClass().getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("tethering") && (n.contains("start") || n.contains("stop")))
                        System.out.println("tm: " + m.getName() + " (" + m.getParameterCount() + " args)");
                }
                System.out.println("currentIsOn=" + isOn.invoke(semWm));
            } else {
                System.out.println("usage: get|stop|start [ssid pass band]|config <ssid pass band>|probe");
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
        }
        System.exit(0);
    }
}
