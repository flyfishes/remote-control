package com.s20.helper;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

/**
 * AOSP 通用热点 helper（以 uid 1000 / system 身份运行）。
 * 适用于 Android 6.x（如乐视 1 Pro）：无 TetheringManager / SoftApConfiguration，
 * 热点入口为 WifiManager 的 @hide API setWifiApEnabled / setWifiApConfiguration。
 *
 * 用法（与三星 helper Main.java 保持一致的输出协议）:
 *   get                     - 读当前状态与持久化配置（SSID= / PASSWORD= / APSTATE=）
 *   stop                    - 关闭热点
 *   start [ssid pass band]  - 有参数则先持久化再启动；无参数用已持久化配置
 *   config <ssid> <pass> <band> - 仅持久化配置（band 在 6.0 上仅支持 2.4G，忽略 5g）
 * 输出约定: RESULT=true 表示达成目标状态；异常输出 FATAL。
 */
public class LetvMain {

    private static Context getSystemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
    }

    private static WifiManager getWm(Context ctx) throws Exception {
        return (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
    }

    /** 反射取 getWifiApState（@hide，返回 int，10~14） */
    private static int apState(WifiManager wm) throws Exception {
        Object r = WifiManager.class.getMethod("getWifiApState").invoke(wm);
        return r instanceof Integer ? (Integer) r : -1;
    }

    private static WifiConfiguration buildConfig(String ssid, String pass, String band) {
        WifiConfiguration wc = new WifiConfiguration();
        wc.SSID = ssid;
        if (pass.isEmpty()) {
            wc.allowedKeyManagement.clear();
            wc.allowedKeyManagement.set(0); // OPEN
        } else {
            wc.preSharedKey = pass;
            wc.allowedKeyManagement.clear();
            wc.allowedKeyManagement.set(4); // WPA2_PSK
        }
        // Android 6.0 软 AP 仅 2.4G，band 参数忽略
        return wc;
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        String ssid = args.length > 1 ? args[1] : "";
        String pass = args.length > 2 ? args[2] : "";
        String band = args.length > 3 ? args[3] : "";
        try {
            android.os.Looper.prepareMainLooper();
            Context ctx = getSystemContext();
            WifiManager wm = getWm(ctx);
            Method_getCfg getCfg = new Method_getCfg(wm);
            Method_setCfg setCfg = new Method_setCfg(wm);

            if (mode.equals("get")) {
                System.out.println("APSTATE=" + apState(wm));
                WifiConfiguration cfg = getCfg.get();
                if (cfg == null) {
                    System.out.println("SSID=");
                    System.out.println("PASSWORD=");
                } else {
                    String s = cfg.SSID == null ? "" : cfg.SSID.replaceAll("^\"|\"$", "");
                    System.out.println("SSID=" + s);
                    System.out.println("PASSWORD=" + (cfg.preSharedKey == null ? "" : cfg.preSharedKey));
                }
            } else if (mode.equals("stop")) {
                try {
                    Object r = WifiManager.class.getMethod("setWifiApEnabled",
                            WifiConfiguration.class, boolean.class).invoke(wm, null, false);
                    System.out.println("setWifiApEnabled(false) = " + r);
                } catch (Throwable t) {
                    System.out.println("stop invoke: " + t);
                }
                Thread.sleep(2500);
                int st = apState(wm);
                System.out.println("finalState=" + st);
                System.out.println("RESULT=" + (st == 11));
            } else if (mode.equals("start")) {
                WifiConfiguration cfg;
                if (!ssid.isEmpty()) {
                    cfg = buildConfig(ssid, pass, band);
                    try { setCfg.set(cfg); System.out.println("persisted ok"); }
                    catch (Throwable t) { System.out.println("WARN persist failed: " + t); }
                } else {
                    cfg = getCfg.get();
                    if (cfg == null) {
                        System.out.println("FATAL no persisted config and no args given");
                        System.exit(0);
                        return;
                    }
                    System.out.println("usingPersisted SSID=" + (cfg.SSID == null ? "" : cfg.SSID));
                }
                if (apState(wm) == 13) {
                    System.out.println("hotspot already on, stop first");
                    WifiManager.class.getMethod("setWifiApEnabled",
                            WifiConfiguration.class, boolean.class).invoke(wm, null, false);
                    Thread.sleep(3000);
                }
                try {
                    Object r = WifiManager.class.getMethod("setWifiApEnabled",
                            WifiConfiguration.class, boolean.class).invoke(wm, cfg, true);
                    System.out.println("setWifiApEnabled(true) = " + r);
                } catch (Throwable t) {
                    System.out.println("start invoke: " + t);
                }
                Thread.sleep(4000);
                int st = apState(wm);
                System.out.println("finalState=" + st);
                System.out.println("RESULT=" + (st == 13));
            } else if (mode.equals("config")) {
                WifiConfiguration cfg = buildConfig(ssid, pass, band);
                setCfg.set(cfg);
                System.out.println("setWifiApConfiguration done");
                WifiConfiguration rb = getCfg.get();
                System.out.println("readback SSID=" + (rb == null || rb.SSID == null ? "" : rb.SSID));
            } else {
                System.out.println("usage: get|stop|start [ssid pass band]|config <ssid pass band>");
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
        }
        System.exit(0);
    }

    // 小包装：反射 setWifiApConfiguration（@hide，6.0 存在），失败则视为不支持
    private static class Method_getCfg {
        final WifiManager wm;
        Method_getCfg(WifiManager wm) { this.wm = wm; }
        WifiConfiguration get() {
            try {
                return (WifiConfiguration) WifiManager.class.getMethod("getWifiApConfiguration").invoke(wm);
            } catch (Throwable t) {
                System.out.println("getWifiApConfiguration: " + t);
                return null;
            }
        }
    }
    private static class Method_setCfg {
        final WifiManager wm;
        Method_setCfg(WifiManager wm) { this.wm = wm; }
        void set(WifiConfiguration cfg) throws Exception {
            WifiManager.class.getMethod("setWifiApConfiguration", WifiConfiguration.class).invoke(wm, cfg);
        }
    }
}
