package com.devicespooflab.hooks.hooks;

import android.accounts.Account;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AccountHooks {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> accountManager = XposedHelpers.findClassIfExists(
                "android.accounts.AccountManager",
                lpparam.classLoader
        );
        if (accountManager == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(accountManager, "getAccountsByType",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String type = (String) param.args[0];
                            if (!"com.google".equals(type)) {
                                return;
                            }
                            String spoofedEmail = ConfigManager.getSpoofedGmail();
                            if (spoofedEmail == null || spoofedEmail.isEmpty()) {
                                return;
                            }
                            Account[] original = (Account[]) param.getResult();
                            if (original != null && original.length > 0) {
                                original[0] = new Account(spoofedEmail, "com.google");
                                param.setResult(original);
                            } else {
                                param.setResult(new Account[]{new Account(spoofedEmail, "com.google")});
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(accountManager, "getAccounts",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String spoofedEmail = ConfigManager.getSpoofedGmail();
                            if (spoofedEmail == null || spoofedEmail.isEmpty()) {
                                return;
                            }
                            Account[] original = (Account[]) param.getResult();
                            if (original == null || original.length == 0) {
                                return;
                            }
                            boolean hasGoogle = false;
                            for (Account acc : original) {
                                if ("com.google".equals(acc.type)) {
                                    hasGoogle = true;
                                    break;
                                }
                            }
                            if (!hasGoogle) {
                                return;
                            }
                            Account[] modified = new Account[original.length];
                            for (int i = 0; i < original.length; i++) {
                                if ("com.google".equals(original[i].type)) {
                                    modified[i] = new Account(spoofedEmail, "com.google");
                                } else {
                                    modified[i] = original[i];
                                }
                            }
                            param.setResult(modified);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
    }
}
