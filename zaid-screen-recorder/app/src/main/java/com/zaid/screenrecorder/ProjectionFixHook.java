package com.zaid.screenrecorder;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Capture-pipeline fixes shared by the recorder and supported mirroring apps.
 *
 * Consent UI replacement intentionally lives only in SystemUiProjectionDialogHook. Keeping
 * one owner avoids duplicate hooks/races between HyperOS ClassLoaders.
 */
public class ProjectionFixHook implements IXposedHookLoadPackage {
    private static final String TAG = "ZaidScreenRecorder";

    private static final Set<String> PROJECTION_CLIENTS = new HashSet<>(Arrays.asList(
            "com.zhiliaoapp.musically",
            "com.xiaomi.mirror",
            "com.google.android.apps.chromecast.app",
            "com.miui.mishare.connectivity",
            "com.gxdevs.screenx",
            "com.miui.screenrecorder"
    ));

    private static final Set<String> TARGETS = new HashSet<>(PROJECTION_CLIENTS);
    static {
        TARGETS.add("com.android.systemui");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGETS.contains(lpparam.packageName)) return;

        log("loaded package=" + lpparam.packageName + " process=" + lpparam.processName);
        installFullDisplayProjectionHook(lpparam.packageName);
        installVirtualDisplayGuard(lpparam.packageName);

        if ("com.android.systemui".equals(lpparam.packageName)) {
            installSystemUiRedirect();
            log("SystemUI consent UI delegated exclusively to v1.4 runtime hook");
        }
    }

    private void installFullDisplayProjectionHook(String pkg) {
        try {
            Class<?> configClass = XposedHelpers.findClassIfExists(
                    "android.media.projection.MediaProjectionConfig", null);
            if (configClass == null) {
                log(pkg + ": MediaProjectionConfig unavailable");
                return;
            }

            Method withConfig = MediaProjectionManager.class.getDeclaredMethod(
                    "createScreenCaptureIntent", configClass);
            withConfig.setAccessible(true);

            XposedBridge.hookMethod(withConfig, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object full = XposedHelpers.callStaticMethod(
                                configClass, "createConfigForDefaultDisplay");
                        param.args[0] = full;
                        log(pkg + ": force MediaProjectionConfig=DEFAULT_DISPLAY");
                    } catch (Throwable t) {
                        log(pkg + ": force config failed " + t);
                    }
                }
            });

            Method noArgs = MediaProjectionManager.class.getDeclaredMethod(
                    "createScreenCaptureIntent");
            noArgs.setAccessible(true);
            XposedBridge.hookMethod(noArgs, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object full = XposedHelpers.callStaticMethod(
                                configClass, "createConfigForDefaultDisplay");
                        Object intent = XposedBridge.invokeOriginalMethod(
                                withConfig, param.thisObject, new Object[]{full});
                        param.setResult(intent);
                        log(pkg + ": replaced no-arg capture intent with DEFAULT_DISPLAY");
                    } catch (Throwable t) {
                        log(pkg + ": no-arg capture intent replacement failed " + t);
                    }
                }
            });

            log(pkg + ": MediaProjection full-display hooks installed");
        } catch (Throwable t) {
            log(pkg + ": MediaProjection hook install failed " + t);
        }
    }

    private void installVirtualDisplayGuard(String pkg) {
        try {
            XposedBridge.hookAllMethods(MediaProjection.class, "createVirtualDisplay",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 4) return;
                            if (!(param.args[0] instanceof String)
                                    || !(param.args[1] instanceof Integer)
                                    || !(param.args[2] instanceof Integer)
                                    || !(param.args[3] instanceof Integer)) return;

                            String name = (String) param.args[0];
                            int width = (Integer) param.args[1];
                            int height = (Integer) param.args[2];
                            int dpi = (Integer) param.args[3];
                            int[] real = realDisplay();
                            int fixedW = width;
                            int fixedH = height;

                            // TikTok's RTC pipeline may intentionally scale its VirtualDisplay.
                            // Keep its dimensions untouched; only its consent mode is forced to
                            // the default/full display.
                            if (real[0] > 0 && real[1] > 0
                                    && !"com.zhiliaoapp.musically".equals(pkg)) {
                                if (width * 2 == real[0] && height == real[1]) fixedW = real[0];
                                if (height * 2 == real[1] && width == real[0]) fixedH = real[1];
                                if (width * 2 == real[1] && height == real[0]) fixedW = real[1];
                                if (height * 2 == real[0] && width == real[1]) fixedH = real[0];
                            }

                            if (fixedW != width || fixedH != height) {
                                param.args[1] = fixedW;
                                param.args[2] = fixedH;
                                log(pkg + ": fixed VirtualDisplay " + name + " "
                                        + width + "x" + height + " -> "
                                        + fixedW + "x" + fixedH + " dpi=" + dpi);
                            } else {
                                log(pkg + ": VirtualDisplay " + name + " "
                                        + width + "x" + height + " dpi=" + dpi
                                        + " real=" + real[0] + "x" + real[1]);
                            }
                        }
                    });
            log(pkg + ": VirtualDisplay guard installed");
        } catch (Throwable t) {
            log(pkg + ": VirtualDisplay hook install failed " + t);
        }
    }

    private void installSystemUiRedirect() {
        try {
            Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", null);
            XC_MethodHook redirect = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        if (!(arg instanceof Intent)) continue;
                        Intent intent = (Intent) arg;

                        String target = intent.getPackage();
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        }
                        if (!"com.miui.screenrecorder".equals(target)) continue;

                        Intent replacement = new Intent();
                        replacement.setClassName(
                                "com.zaid.screenrecorder",
                                "com.zaid.screenrecorder.MainActivity");
                        replacement.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        param.args[i] = replacement;
                        log("SystemUI redirected MIUI Screen Recorder -> Zaid Screen Recorder");
                    }
                }
            };

            XposedBridge.hookAllMethods(contextImpl, "startActivity", redirect);
            XposedBridge.hookAllMethods(contextImpl, "startActivityAsUser", redirect);
            log("SystemUI recorder redirect installed");
        } catch (Throwable t) {
            log("SystemUI redirect install failed " + t);
        }
    }

    private int[] realDisplay() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object current = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (!(current instanceof Application)) return new int[]{0, 0};

            Application app = (Application) current;
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return new int[]{0, 0};

            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            return new int[]{metrics.widthPixels, metrics.heightPixels};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
