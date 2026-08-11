package com.zaid.tiktoklivequality;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class TikTokLiveQualityHook implements IXposedHookLoadPackage {
    private static final String TAG = "TikTokLiveQuality";
    private static final String TARGET_PACKAGE = "com.zhiliaoapp.musically";
    private static final String SCREEN_CAPTURE_NAME = "WebRTC_ScreenCapture";

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
    private static final Set<ClassLoader> PROBED_LOADERS = Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());
    private static final AtomicBoolean DISCOVERY_HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_HOOKS_INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("Loaded TikTok process=" + lpparam.processName + " loader=" + describeLoader(lpparam.classLoader));

        installDynamicClassDiscoveryHooks();
        installFrameworkFallbackHooks(lpparam.classLoader);
        probeLoader(lpparam.classLoader, "initial TikTok loader");
    }

    /**
     * TikTok's live_cast feature is delivered as a dynamic split / dex container.  The
     * original v1.0 only observed java.lang.ClassLoader.loadClass(), which can miss
     * classes resolved through Android's BaseDexClassLoader/DexFile path.  v1.1 watches
     * all of those paths and probes newly-created dex class loaders as well.
     */
    private static void installDynamicClassDiscoveryHooks() {
        if (!DISCOVERY_HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", classResultHook("ClassLoader.loadClass"));
            log("Discovery hook installed: ClassLoader.loadClass");
        } catch (Throwable t) {
            log("Discovery hook failed: ClassLoader.loadClass: " + t);
        }

        hookClassResultMethods("dalvik.system.BaseDexClassLoader", "findClass");
        hookClassResultMethods("dalvik.system.DexFile", "loadClass");
        hookClassResultMethods("dalvik.system.DexFile", "loadClassBinaryName");

        hookLoaderConstructors("dalvik.system.BaseDexClassLoader");
        hookLoaderConstructors("dalvik.system.PathClassLoader");
        hookLoaderConstructors("dalvik.system.DexClassLoader");
        hookLoaderConstructors("dalvik.system.InMemoryDexClassLoader");
        hookLoaderConstructors("dalvik.system.DelegateLastClassLoader");
    }

    private static void hookClassResultMethods(String className, String methodName) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, null);
            if (clazz == null) {
                log("Discovery class unavailable: " + className);
                return;
            }
            XposedBridge.hookAllMethods(clazz, methodName, classResultHook(className + "." + methodName));
            log("Discovery hook installed: " + className + "." + methodName);
        } catch (Throwable t) {
            log("Discovery hook failed: " + className + "." + methodName + ": " + t);
        }
    }

    private static XC_MethodHook classResultHook(final String source) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }
                Object result = param.getResult();
                if (result instanceof Class<?>) {
                    Class<?> clazz = (Class<?>) result;
                    if (TARGET_CLASSES.contains(clazz.getName())) {
                        log("Discovered target via " + source + ": " + clazz.getName() + " loader=" + describeLoader(clazz.getClassLoader()));
                        installForClass(clazz);
                    }
                }
            }
        };
    }

    private static void hookLoaderConstructors(final String className) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, null);
            if (clazz == null) {
                return;
            }
            XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ClassLoader) {
                        ClassLoader loader = (ClassLoader) param.thisObject;
                        log("Observed loader " + className + " -> " + describeLoader(loader));
                        probeLoader(loader, className + " constructor");
                    }
                }
            });
        } catch (Throwable t) {
            log("Loader constructor hook failed: " + className + ": " + t);
        }
    }

    private static void probeLoader(ClassLoader loader, String source) {
        if (loader == null || !PROBED_LOADERS.add(loader)) {
            return;
        }

        log("Probing loader from " + source + ": " + describeLoader(loader));
        for (String className : TARGET_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className, false, loader);
                log("Probe found " + className + " via " + source);
                installForClass(clazz);
            } catch (Throwable ignored) {
                // Expected until the dynamic live_cast dex is attached to this loader.
            }
        }
    }

    /**
     * Framework-level fallback.  This guarantees that we can at least see and resize
     * TikTok's WebRTC_ScreenCapture VirtualDisplay even if ByteDance changes its
     * dynamic-feature class loader again.
     */
    private static void installFrameworkFallbackHooks(ClassLoader appLoader) {
        if (!FRAMEWORK_HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> mediaProjection = XposedHelpers.findClass("android.media.projection.MediaProjection", appLoader);
            XposedBridge.hookAllMethods(mediaProjection, "createVirtualDisplay", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 4 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String name = (String) param.args[0];
                    if (!isTikTokScreenCapture(name)) {
                        return;
                    }
                    if (!(param.args[1] instanceof Integer) || !(param.args[2] instanceof Integer)) {
                        return;
                    }

                    int oldW = (Integer) param.args[1];
                    int oldH = (Integer) param.args[2];
                    int[] target = chooseTarget(oldW, oldH);
                    int density = param.args[3] instanceof Integer ? (Integer) param.args[3] : -1;

                    log("MediaProjection.createVirtualDisplay " + name + ": " + oldW + "x" + oldH + " density=" + density
                            + " -> " + target[0] + "x" + target[1]);
                    param.args[1] = target[0];
                    param.args[2] = target[1];
                }
            });
            log("Framework fallback installed: MediaProjection.createVirtualDisplay");
        } catch (Throwable t) {
            log("Framework fallback failed: MediaProjection.createVirtualDisplay: " + t);
        }

        // Diagnostic-only in v1.1: record the actual encoder MediaFormat without
        // changing it.  Once the live_cast hooks are confirmed, this tells us whether
        // the final MediaCodec config keeps 60fps/16Mbps or is adapted downstream.
        try {
            XposedBridge.hookAllMethods(MediaCodec.class, "configure", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 4 || !(param.args[0] instanceof MediaFormat)
                            || !(param.args[3] instanceof Integer)) {
                        return;
                    }
                    int flags = (Integer) param.args[3];
                    if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) == 0) {
                        return;
                    }

                    MediaFormat format = (MediaFormat) param.args[0];
                    String text = String.valueOf(format);
                    if (!isVideoFormat(text) || !hasByteDanceCastFrame()) {
                        return;
                    }
                    log("MediaCodec.configure encoder format=" + text);
                }
            });
            log("Diagnostic hook installed: MediaCodec.configure");
        } catch (Throwable t) {
            log("Diagnostic hook failed: MediaCodec.configure: " + t);
        }
    }

    private static boolean isTikTokScreenCapture(String name) {
        if (name == null) {
            return false;
        }
        return SCREEN_CAPTURE_NAME.equals(name)
                || name.toLowerCase().contains("webrtc_screencapture")
                || name.toLowerCase().contains("screen_capture");
    }

    private static boolean isVideoFormat(String format) {
        if (format == null) {
            return false;
        }
        String lower = format.toLowerCase();
        return lower.contains("video/") || lower.contains("mime=video");
    }

    private static boolean hasByteDanceCastFrame() {
        try {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                String name = element.getClassName();
                if (name.startsWith("com.byted.cast.") || name.contains("ScreenRecorder") || name.contains("WebRTC")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
            log("Hooked " + clazz.getName() + " loader=" + describeLoader(clazz.getClassLoader()));
        } catch (Throwable t) {
            INSTALLED.remove(clazz);
            log("Failed hooking " + clazz.getName() + ": " + t);
        }
    }

    private static void hookScreenProfile(Class<?> clazz) {
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                forceProfile(param.thisObject, "RTCScreenProfile.constructor");
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

            log(source + " profile -> " + target[0] + "x" + target[1] + " @ " + TARGET_FPS + "fps, "
                    + TARGET_BITRATE_KBPS + "/" + TARGET_MAX_BITRATE_KBPS + " kbps");
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

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "<boot>";
        }
        try {
            return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
        } catch (Throwable ignored) {
            return String.valueOf(loader);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
