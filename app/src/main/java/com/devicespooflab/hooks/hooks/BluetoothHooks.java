package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class BluetoothHooks {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> bluetoothAdapter = XposedHelpers.findClassIfExists(
                "android.bluetooth.BluetoothAdapter",
                lpparam.classLoader
        );
        if (bluetoothAdapter == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(bluetoothAdapter, "getAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String spoofed = ConfigManager.getBluetoothMac();
                            if (spoofed != null) {
                                param.setResult(spoofed);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
    }
}
