package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WifiHooks {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wifiInfo = XposedHelpers.findClassIfExists(
                "android.net.wifi.WifiInfo",
                lpparam.classLoader
        );
        if (wifiInfo == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(wifiInfo, "getMacAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String spoofed = ConfigManager.getWifiMac();
                            if (spoofed != null) {
                                param.setResult(spoofed);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(wifiInfo, "getSSID",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String spoofed = ConfigManager.getWifiSsid();
                            if (spoofed != null) {
                                param.setResult(spoofed);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        Class<?> wifiManager = XposedHelpers.findClassIfExists(
                "android.net.wifi.WifiManager",
                lpparam.classLoader
        );
        if (wifiManager == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(wifiManager, "getConnectionInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result == null) {
                                return;
                            }
                            String spoofedMac = ConfigManager.getWifiMac();
                            String spoofedSsid = ConfigManager.getWifiSsid();
                            if (spoofedMac != null) {
                                try {
                                    XposedHelpers.setObjectField(result, "mMacAddress", spoofedMac);
                                } catch (Throwable ignored) {
                                }
                            }
                            if (spoofedSsid != null) {
                                try {
                                    XposedHelpers.setObjectField(result, "mWifiSsid", spoofedSsid);
                                } catch (Throwable ignored) {
                                }
                                try {
                                    XposedHelpers.setObjectField(result, "mSSID", spoofedSsid);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
    }
}
