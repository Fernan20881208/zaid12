package com.zaid.screenrecorder;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
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
 * v1.5 capture-engine fix for HyperOS 3 / duchamp.
 *
 * The stock MediaProjection consent UI is intentionally left to Android/SystemUI. This
 * hook runs inside the actual capture clients and fixes the pipeline after consent:
 * MediaProjection -> VirtualDisplay -> VirtualDisplay.resize -> MediaCodec.configure.
 */
public class ProjectionFixHook implements IXposedHookLoadPackage {
    private static final String TAG = "ZaidScreenRecorder";
    private static final String V = "v1.5";

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

        log(V + " loaded package=" + lpparam.packageName + " process=" + lpparam.processName);
        installFullDisplayProjectionHook(lpparam.packageName);
        installVirtualDisplayGuard(lpparam.packageName);
        installVirtualDisplayResizeGuard(lpparam.packageName);
        installMediaCodecGuard(lpparam.packageName);

        if ("com.android.systemui".equals(lpparam.packageName)) {
            installSystemUiRedirect();
            log(V + " SystemUI keeps stock MediaProjection consent UI");
        }
    }

    private void installFullDisplayProjectionHook(String pkg) {
        try {
            Class<?> configClass = XposedHelpers.findClassIfExists(
                    "android.media.projection.MediaProjectionConfig", null);
            if (configClass == null) {
                log(V + " " + pkg + ": MediaProjectionConfig unavailable");
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
                        log(V + " " + pkg + ": force MediaProjectionConfig=DEFAULT_DISPLAY");
                    } catch (Throwable t) {
                        log(V + " " + pkg + ": force config failed " + t);
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
                        log(V + " " + pkg + ": replaced no-arg capture intent with DEFAULT_DISPLAY");
                    } catch (Throwable t) {
                        log(V + " " + pkg + ": no-arg capture intent replacement failed " + t);
                    }
                }
            });

            log(V + " " + pkg + ": MediaProjection full-display hooks installed");
        } catch (Throwable t) {
            log(V + " " + pkg + ": MediaProjection hook install failed " + t);
        }
    }

    /**
     * Fixes only the characteristic half-screen geometry: one axis is exactly half of the
     * current display while the other axis is already the full corresponding axis. Scaled
     * VirtualDisplays that preserve both dimensions/aspect are intentionally left alone.
     */
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
                            int[] fixed = fixHalfGeometry(width, height, real[0], real[1]);

                            if (fixed[0] != width || fixed[1] != height) {
                                param.args[1] = fixed[0];
                                param.args[2] = fixed[1];
                                log(V + " " + pkg + ": HALF-SCREEN VirtualDisplay FIX " + name
                                        + " " + width + "x" + height + " -> "
                                        + fixed[0] + "x" + fixed[1] + " dpi=" + dpi
                                        + " real=" + real[0] + "x" + real[1]);
                            } else {
                                log(V + " " + pkg + ": VirtualDisplay " + name + " "
                                        + width + "x" + height + " dpi=" + dpi
                                        + " real=" + real[0] + "x" + real[1]);
                            }
                        }
                    });
            log(V + " " + pkg + ": VirtualDisplay create guard installed");
        } catch (Throwable t) {
            log(V + " " + pkg + ": VirtualDisplay hook install failed " + t);
        }
    }

    private void installVirtualDisplayResizeGuard(String pkg) {
        try {
            XposedBridge.hookAllMethods(VirtualDisplay.class, "resize", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 3) return;
                    if (!(param.args[0] instanceof Integer)
                            || !(param.args[1] instanceof Integer)
                            || !(param.args[2] instanceof Integer)) return;

                    int width = (Integer) param.args[0];
                    int height = (Integer) param.args[1];
                    int dpi = (Integer) param.args[2];
                    int[] real = realDisplay();
                    int[] fixed = fixHalfGeometry(width, height, real[0], real[1]);

                    if (fixed[0] != width || fixed[1] != height) {
                        param.args[0] = fixed[0];
                        param.args[1] = fixed[1];
                        log(V + " " + pkg + ": HALF-SCREEN VirtualDisplay.resize FIX "
                                + width + "x" + height + " -> " + fixed[0] + "x" + fixed[1]
                                + " dpi=" + dpi + " real=" + real[0] + "x" + real[1]);
                    } else {
                        log(V + " " + pkg + ": VirtualDisplay.resize " + width + "x" + height
                                + " dpi=" + dpi + " real=" + real[0] + "x" + real[1]);
                    }
                }
            });
            log(V + " " + pkg + ": VirtualDisplay resize guard installed");
        } catch (Throwable t) {
            log(V + " " + pkg + ": VirtualDisplay resize hook failed " + t);
        }
    }

    /**
     * MediaCodec safety net. We do not blindly force every encoder to native resolution.
     * We only mutate unmistakable half-screen cases and crop metadata that clips exactly
     * one half of an otherwise valid video frame.
     */
    private void installMediaCodecGuard(String pkg) {
        try {
            XposedBridge.hookAllMethods(MediaCodec.class, "configure", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof MediaFormat)) return;

                    MediaFormat format = (MediaFormat) param.args[0];
                    String mime = safeString(format, MediaFormat.KEY_MIME);
                    if (mime == null || !mime.startsWith("video/")) return;

                    boolean encoder = isEncoderConfigure(param.args);
                    int width = safeInt(format, MediaFormat.KEY_WIDTH, -1);
                    int height = safeInt(format, MediaFormat.KEY_HEIGHT, -1);
                    int[] real = realDisplay();

                    int cropLeft = safeInt(format, "crop-left", -1);
                    int cropRight = safeInt(format, "crop-right", -1);
                    int cropTop = safeInt(format, "crop-top", -1);
                    int cropBottom = safeInt(format, "crop-bottom", -1);

                    log(V + " " + pkg + ": MediaCodec.configure mime=" + mime
                            + " encoder=" + encoder + " frame=" + width + "x" + height
                            + " crop=" + cropLeft + "," + cropTop + "-"
                            + cropRight + "," + cropBottom
                            + " real=" + real[0] + "x" + real[1]);

                    if (!encoder) return;

                    boolean changed = false;

                    // Remove crop metadata only if it clips exactly one half of the encoded
                    // frame (or is clearly outside the encoded bounds).
                    if (width > 0 && height > 0 && hasAnyCrop(
                            cropLeft, cropRight, cropTop, cropBottom)) {
                        int left = cropLeft >= 0 ? cropLeft : 0;
                        int top = cropTop >= 0 ? cropTop : 0;
                        int right = cropRight >= 0 ? cropRight : width - 1;
                        int bottom = cropBottom >= 0 ? cropBottom : height - 1;
                        int cropW = right - left + 1;
                        int cropH = bottom - top + 1;

                        boolean invalid = left < 0 || top < 0 || right >= width || bottom >= height
                                || cropW <= 0 || cropH <= 0;
                        boolean half = (cropW * 2 == width && cropH == height)
                                || (cropH * 2 == height && cropW == width);

                        if (invalid || half) {
                            removeCropKeys(format);
                            changed = true;
                            log(V + " " + pkg + ": HALF-SCREEN MediaCodec crop REMOVED "
                                    + "frame=" + width + "x" + height + " crop="
                                    + cropW + "x" + cropH + " invalid=" + invalid);
                        }
                    }

                    // Only force encoder width/height when exactly one axis is half and the
                    // other is already full. Normal downscaling (e.g. 1080p from 1220x2712)
                    // is preserved.
                    if (width > 0 && height > 0 && real[0] > 0 && real[1] > 0) {
                        int[] fixed = fixHalfGeometry(width, height, real[0], real[1]);
                        if (fixed[0] != width || fixed[1] != height) {
                            format.setInteger(MediaFormat.KEY_WIDTH, fixed[0]);
                            format.setInteger(MediaFormat.KEY_HEIGHT, fixed[1]);
                            changed = true;
                            log(V + " " + pkg + ": HALF-SCREEN MediaCodec frame FIX "
                                    + width + "x" + height + " -> "
                                    + fixed[0] + "x" + fixed[1]);
                        }
                    }

                    if (changed) {
                        param.args[0] = format;
                        log(V + " " + pkg + ": MediaCodec.configure patched format=" + format);
                    }
                }
            });
            log(V + " " + pkg + ": MediaCodec half-screen guard installed");
        } catch (Throwable t) {
            log(V + " " + pkg + ": MediaCodec hook install failed " + t);
        }
    }

    private boolean isEncoderConfigure(Object[] args) {
        if (args == null) return false;
        // Public MediaCodec.configure overloads carry flags as an int. CONFIGURE_FLAG_ENCODE=1.
        for (int i = args.length - 1; i >= 1; i--) {
            if (args[i] instanceof Integer) {
                int flags = (Integer) args[i];
                return (flags & MediaCodec.CONFIGURE_FLAG_ENCODE) != 0;
            }
        }
        return false;
    }

    private int[] fixHalfGeometry(int width, int height, int realW, int realH) {
        if (width <= 0 || height <= 0 || realW <= 0 || realH <= 0) {
            return new int[]{width, height};
        }

        // Match the requested orientation to the physical display orientation.
        int targetW;
        int targetH;
        if ((width >= height) == (realW >= realH)) {
            targetW = realW;
            targetH = realH;
        } else {
            targetW = realH;
            targetH = realW;
        }

        int fixedW = width;
        int fixedH = height;

        if (width * 2 == targetW && height == targetH) fixedW = targetW;
        if (height * 2 == targetH && width == targetW) fixedH = targetH;

        return new int[]{fixedW, fixedH};
    }

    private boolean hasAnyCrop(int l, int r, int t, int b) {
        return l >= 0 || r >= 0 || t >= 0 || b >= 0;
    }

    private void removeCropKeys(MediaFormat format) {
        try { format.removeKey("crop-left"); } catch (Throwable ignored) {}
        try { format.removeKey("crop-right"); } catch (Throwable ignored) {}
        try { format.removeKey("crop-top"); } catch (Throwable ignored) {}
        try { format.removeKey("crop-bottom"); } catch (Throwable ignored) {}
    }

    private int safeInt(MediaFormat format, String key, int fallback) {
        try {
            if (format.containsKey(key)) return format.getInteger(key);
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private String safeString(MediaFormat format, String key) {
        try {
            if (format.containsKey(key)) return format.getString(key);
        } catch (Throwable ignored) {
        }
        return null;
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
                        log(V + " SystemUI redirected MIUI Screen Recorder -> Zaid Screen Recorder");
                    }
                }
            };

            XposedBridge.hookAllMethods(contextImpl, "startActivity", redirect);
            XposedBridge.hookAllMethods(contextImpl, "startActivityAsUser", redirect);
            log(V + " SystemUI recorder redirect installed");
        } catch (Throwable t) {
            log(V + " SystemUI redirect install failed " + t);
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
