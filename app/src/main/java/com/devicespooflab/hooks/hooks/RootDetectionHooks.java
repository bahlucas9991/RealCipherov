package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;

public class RootDetectionHooks {

    private static final String[] ROOT_PATHS = {
        "/su", "/system/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/su", "/data/local/xbin/su",
        "/sbin/su", "/su/bin/su", "/magisk", "/sbin/magisk",
        "/system/app/Superuser.apk", "/system/etc/init.d/99SuperSUDaemon",
        "/system/etc/init.d/99SuperSU", "/system/app/KingUser.apk",
        "/system/app/KingRoot.apk"
    };

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ConfigManager.isHideRootEnabled()) {
            return;
        }

        Class<?> fileClass = XposedHelpers.findClassIfExists(
                "java.io.File",
                lpparam.classLoader
        );
        if (fileClass == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(fileClass, "exists",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            File file = (File) param.thisObject;
                            String path = file.getAbsolutePath();
                            for (String rootPath : ROOT_PATHS) {
                                if (path.equalsIgnoreCase(rootPath)) {
                                    param.setResult(false);
                                    return;
                                }
                            }
                            if (path.toLowerCase().contains("magisk")
                                    || path.toLowerCase().contains("supersu")
                                    || path.toLowerCase().contains("superuser")) {
                                param.setResult(false);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
    }
}
