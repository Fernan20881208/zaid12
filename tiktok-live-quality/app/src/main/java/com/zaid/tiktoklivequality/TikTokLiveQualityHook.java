package com.zaid.tiktoklivequality;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class TikTokLiveQualityHook implements IXposedHookLoadPackage {
    private static final String TAG = "TikTokLiveQuality";
    private static final String TARGET_PACKAGE = "com.zhiliaoapp.musically";

    private static final int TARGET_FPS = 60;
    private static final int TARGET_BITRATE_KBPS = 16000;
    private static final int TARGET_MAX_BITRATE_KBPS = 19200;
    private static final int LANDSCAPE_WIDTH = 1920;
    private static final int LANDSCAPE_HEIGHT = 864;

    private static final Set<String> TARGET_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.byted.cast.sdk.RTCScreenProfile",
            "com.byted.cast.sdk.core.RTCEngineImpl",
            "com.byted.cast.capture.ByteMediaRecorder",
            "com.byted.cast.capture.video.VideoRecorderManager",
            "com.byted.cast.capture.video.screen.ScreenRecorder",
            "com.byted.cast.capture.encoder.VideoEncoder"
    )));

    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("Loaded TikTok process=" + lpparam.processName);

        for (String className : TARGET_CLASSES) {
            tryInstallExisting(className, lpparam.classLoader);
        }

        hookDynamicClassLoading();
    }

    private static void hookDynamicClassLoading() {
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                    return;
                }

                String name = (String) param.args[0];
                if (!TARGET_CLASSES.contains(name)) {
                    return;
                }

                Object result = param.getResult();
                if (result instanceof Class<?>) {
                    installForClass((Class<?>) result);
                }
            }
        });
    }

    private static void tryInstallExisting(String className, ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            installForClass(clazz);
        } catch (Throwable ignored) {
            // ByteDance loads live_cast dynamically. ClassLoader hook will catch it later.
        }
    }

    private static void installForClass(Class<?> clazz) {
        if (clazz == null || !TARGET_CLASSES.contains(clazz.getName()) || !INSTALLED.add(clazz)) {
            return;
        }

        try {
            switch (clazz.getName()) {
                case "com.byted.cast.sdk.RTCScreenProfile":
                    hookScreenProfile(clazz);
                    break;
                case "com.byted.cast.sdk.core.RTCEngineImpl":
                    hookRtcEngine(clazz);
                    break;
                case "com.byted.cast.capture.ByteMediaRecorder":
                    hookByteMediaRecorder(clazz);
                    break;
                case "com.byted.cast.capture.video.VideoRecorderManager":
                    hookVideoRecorderManager(clazz);
                    break;
                case "com.byted.cast.capture.video.screen.ScreenRecorder":
                    hookScreenRecorderLogging(clazz);
                    break;
                case "com.byted.cast.capture.encoder.VideoEncoder":
                    hookVideoEncoderLogging(clazz);
                    break;
                default:
                    break;
            }
            log("Hooked " + clazz.getName());
        } catch (Throwable t) {
            INSTALLED.remove(clazz);
            log("Failed hooking " + clazz.getName() + ": " + t);
        }
    }

    private static void hookScreenProfile(Class<?> clazz) {
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                forceProfile(param.thisObject, "constructor");
            }
        });

        XposedBridge.hookAllMethods(clazz, "setResolution", forceResolutionArgs("RTCScreenProfile.setResolution"));
        XposedBridge.hookAllMethods(clazz, "setmFps", forceSingleInt(TARGET_FPS, "RTCScreenProfile.setmFps"));
        XposedBridge.hookAllMethods(clazz, "setBitrate", forceBitratePair("RTCScreenProfile.setBitrate"));

        XposedBridge.hookAllMethods(clazz, "setFixedResolution", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length >= 1 && param.args[0] instanceof Boolean) {
                    if (!Boolean.TRUE.equals(param.args[0])) {
                        log("RTCScreenProfile.setFixedResolution: " + param.args[0] + " -> true");
                    }
                    param.args[0] = true;
                }
            }
        });
    }

    private static void hookRtcEngine(Class<?> clazz) {
        XposedBridge.hookAllMethods(clazz, "setScreenProfile", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) {
                    forceProfile(param.args[0], "RTCEngineImpl.setScreenProfile");
                }
            }
        });

        XposedBridge.hookAllMethods(clazz, "setVirtualDisplayWH", forceResolutionArgs("RTCEngineImpl.setVirtualDisplayWH"));
    }

    private static void hookByteMediaRecorder(Class<?> clazz) {
        XposedBridge.hookAllMethods(clazz, "setFps", forceSingleInt(TARGET_FPS, "ByteMediaRecorder.setFps"));
        XposedBridge.hookAllMethods(clazz, "setBitrateKps", forceSingleInt(TARGET_BITRATE_KBPS, "ByteMediaRecorder.setBitrateKps"));
        XposedBridge.hookAllMethods(clazz, "setVirtualDisplayWH", forceResolutionArgs("ByteMediaRecorder.setVirtualDisplayWH"));
    }

    private static void hookVideoRecorderManager(Class<?> clazz) {
        XposedBridge.hookAllMethods(clazz, "setFps", forceSingleInt(TARGET_FPS, "VideoRecorderManager.setFps"));
        XposedBridge.hookAllMethods(clazz, "setBitrateKbps", forceSingleInt(TARGET_BITRATE_KBPS, "VideoRecorderManager.setBitrateKbps"));
        XposedBridge.hookAllMethods(clazz, "setVirtualDisplayWH", forceResolutionArgs("VideoRecorderManager.setVirtualDisplayWH"));
    }

    private static void hookScreenRecorderLogging(Class<?> clazz) {
        XposedBridge.hookAllMethods(clazz, "tryEncoder", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                log("ScreenRecorder.tryEncoder args=" + Arrays.toString(param.args));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) {
                    log("ScreenRecorder.tryEncoder result=" + param.getResult());
                }
            }
        });
    }

    private static void hookVideoEncoderLogging(Class<?> clazz) {
        XposedBridge.hookAllMethods(clazz, "prepareVideoEncoder", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                log("VideoEncoder.prepareVideoEncoder args=" + Arrays.toString(param.args));
            }
        });
    }

    private static XC_MethodHook forceSingleInt(final int value, final String source) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length >= 1 && param.args[0] instanceof Integer) {
                    int old = (Integer) param.args[0];
                    if (old != value) {
                        log(source + ": " + old + " -> " + value);
                    }
                    param.args[0] = value;
                }
            }
        };
    }

    private static XC_MethodHook forceBitratePair(final String source) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && param.args[0] instanceof Integer && param.args[1] instanceof Integer) {
                    int oldTarget = (Integer) param.args[0];
                    int oldMax = (Integer) param.args[1];
                    if (oldTarget != TARGET_BITRATE_KBPS || oldMax != TARGET_MAX_BITRATE_KBPS) {
                        log(source + ": " + oldTarget + "/" + oldMax + " -> " + TARGET_BITRATE_KBPS + "/" + TARGET_MAX_BITRATE_KBPS);
                    }
                    param.args[0] = TARGET_BITRATE_KBPS;
                    param.args[1] = TARGET_MAX_BITRATE_KBPS;
                }
            }
        };
    }

    private static XC_MethodHook forceResolutionArgs(final String source) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 2 || !(param.args[0] instanceof Integer) || !(param.args[1] instanceof Integer)) {
                    return;
                }

                int oldW = (Integer) param.args[0];
                int oldH = (Integer) param.args[1];
                int[] target = chooseTarget(oldW, oldH);

                if (oldW != target[0] || oldH != target[1]) {
                    log(source + ": " + oldW + "x" + oldH + " -> " + target[0] + "x" + target[1]);
                }

                param.args[0] = target[0];
                param.args[1] = target[1];
            }
        };
    }

    private static void forceProfile(Object profile, String source) {
        if (profile == null) {
            return;
        }

        try {
            int width = getIntField(profile, "mWidth", 0);
            int height = getIntField(profile, "mHeight", 0);
            int[] target = chooseTarget(width, height);

            XposedHelpers.callMethod(profile, "setResolution", target[0], target[1]);
            XposedHelpers.callMethod(profile, "setmFps", TARGET_FPS);
            XposedHelpers.callMethod(profile, "setBitrate", TARGET_BITRATE_KBPS, TARGET_MAX_BITRATE_KBPS);
            XposedHelpers.callMethod(profile, "setFixedResolution", true);

            log(source + " profile -> " + target[0] + "x" + target[1] + " @ " + TARGET_FPS + "fps, " + TARGET_BITRATE_KBPS + "/" + TARGET_MAX_BITRATE_KBPS + " kbps");
        } catch (Throwable t) {
            log(source + " forceProfile failed: " + t);
        }
    }

    private static int getIntField(Object object, String name, int fallback) {
        try {
            return XposedHelpers.getIntField(object, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int[] chooseTarget(int width, int height) {
        if (width > 0 && height > 0 && width < height) {
            return new int[]{LANDSCAPE_HEIGHT, LANDSCAPE_WIDTH};
        }
        return new int[]{LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT};
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
