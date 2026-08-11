package com.zaid.tiktoklivequality;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexFile;
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

    private static final Set<String> TARGET_SIMPLE_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "RTCScreenProfile",
            "RTCEngineImpl",
            "ByteMediaRecorder",
            "VideoRecorderManager",
            "ScreenRecorder",
            "VideoEncoder"
    )));

    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private static final Set<ClassLoader> PROBED_LOADERS = Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());
    private static final Set<ClassLoader> ENUMERATED_LIVE_LOADERS = Collections.newSetFromMap(new ConcurrentHashMap<ClassLoader, Boolean>());
    private static final AtomicBoolean DISCOVERY_HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean SCREEN_CAPTURE_ACTIVE = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("Loaded TikTok process=" + lpparam.processName + " loader=" + describeLoader(lpparam.classLoader));

        installDynamicClassDiscoveryHooks();
        installFrameworkFallbackHooks(lpparam.classLoader);
        inspectLoader(lpparam.classLoader, "initial TikTok loader", false);
    }

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
        hookDexPathMutations();
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
                if (!(result instanceof Class<?>)) {
                    return;
                }
                Class<?> clazz = (Class<?>) result;
                if (isTargetClassName(clazz.getName())) {
                    log("Discovered target via " + source + ": " + clazz.getName() + " loader=" + describeLoader(clazz.getClassLoader()));
                    installForClass(clazz);
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
                        inspectLoader((ClassLoader) param.thisObject, className + " constructor", false);
                    }
                }
            });
        } catch (Throwable t) {
            log("Loader constructor hook failed: " + className + ": " + t);
        }
    }

    /**
     * Dynamic-feature frameworks can mutate an existing PathClassLoader instead of
     * constructing a new one. v1.2 therefore re-inspects the loader whenever ART adds
     * another dex path. This is the path used by many split APK loaders.
     */
    private static void hookDexPathMutations() {
        try {
            Class<?> baseDex = XposedHelpers.findClassIfExists("dalvik.system.BaseDexClassLoader", null);
            if (baseDex == null) {
                return;
            }
            XposedBridge.hookAllMethods(baseDex, "addDexPath", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ClassLoader) {
                        String added = param.args != null && param.args.length > 0 ? String.valueOf(param.args[0]) : "<unknown>";
                        log("BaseDexClassLoader.addDexPath added=" + added);
                        inspectLoader((ClassLoader) param.thisObject, "BaseDexClassLoader.addDexPath", true);
                    }
                }
            });
            log("Discovery hook installed: BaseDexClassLoader.addDexPath");
        } catch (Throwable t) {
            log("Discovery hook failed: BaseDexClassLoader.addDexPath: " + t);
        }
    }

    private static void inspectLoader(ClassLoader loader, String source, boolean forceProbe) {
        if (loader == null) {
            return;
        }

        String dexPaths = describeDexPaths(loader);
        boolean liveCast = containsLiveCast(dexPaths);

        if (liveCast) {
            log("LIVE_CAST loader via " + source + ": " + describeLoader(loader) + " dex=" + dexPaths);
        } else if (PROBED_LOADERS.add(loader)) {
            log("Observed loader via " + source + ": " + describeLoader(loader));
        }

        if (forceProbe || liveCast || PROBED_LOADERS.contains(loader)) {
            probeTargetNames(loader, source);
        }

        if (liveCast && ENUMERATED_LIVE_LOADERS.add(loader)) {
            enumerateLiveCastDex(loader, source);
        }
    }

    private static void probeTargetNames(ClassLoader loader, String source) {
        for (String className : TARGET_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className, false, loader);
                log("Probe found " + className + " via " + source);
                installForClass(clazz);
            } catch (Throwable ignored) {
                // Expected while the dynamic feature is not attached yet.
            }
        }
    }

    private static void enumerateLiveCastDex(ClassLoader loader, String source) {
        try {
            Set<DexFile> dexFiles = getDexFiles(loader);
            int scanned = 0;
            int matched = 0;

            for (DexFile dexFile : dexFiles) {
                String dexName = safeDexName(dexFile);
                if (!containsLiveCast(dexName)) {
                    continue;
                }

                log("Enumerating live_cast dex=" + dexName + " via " + source);
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    scanned++;
                    if (!isPotentialTargetSimpleName(className)) {
                        continue;
                    }
                    matched++;
                    log("live_cast target candidate=" + className);
                    try {
                        Class<?> clazz = Class.forName(className, false, loader);
                        installForClass(clazz);
                    } catch (Throwable t) {
                        log("Failed loading candidate " + className + ": " + t);
                    }
                }
            }

            log("live_cast dex enumeration complete scanned=" + scanned + " matched=" + matched);
        } catch (Throwable t) {
            log("live_cast dex enumeration failed: " + t);
        }
    }

    private static Set<DexFile> getDexFiles(ClassLoader loader) {
        Set<DexFile> result = new LinkedHashSet<>();
        try {
            Object pathList = XposedHelpers.getObjectField(loader, "pathList");
            Object elementsObject = XposedHelpers.getObjectField(pathList, "dexElements");
            if (!(elementsObject instanceof Object[])) {
                return result;
            }

            for (Object element : (Object[]) elementsObject) {
                if (element == null) {
                    continue;
                }
                try {
                    Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                    if (dexFile instanceof DexFile) {
                        result.add((DexFile) dexFile);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static String describeDexPaths(ClassLoader loader) {
        StringBuilder out = new StringBuilder();
        try {
            for (DexFile dexFile : getDexFiles(loader)) {
                if (out.length() > 0) {
                    out.append('|');
                }
                out.append(safeDexName(dexFile));
            }
        } catch (Throwable ignored) {
        }
        return out.length() == 0 ? "<unknown>" : out.toString();
    }

    private static String safeDexName(DexFile dexFile) {
        try {
            String name = dexFile.getName();
            return name == null ? String.valueOf(dexFile) : name;
        } catch (Throwable t) {
            return String.valueOf(dexFile);
        }
    }

    private static boolean containsLiveCast(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("live_cast") || lower.contains("livecast") || lower.contains("df_live_cast");
    }

    private static boolean isPotentialTargetSimpleName(String className) {
        if (className == null) {
            return false;
        }
        int dot = className.lastIndexOf('.');
        String simple = dot >= 0 ? className.substring(dot + 1) : className;
        return TARGET_SIMPLE_CLASSES.contains(simple);
    }

    private static boolean isTargetClassName(String className) {
        if (TARGET_CLASSES.contains(className)) {
            return true;
        }
        if (className == null || !className.toLowerCase().contains("cast")) {
            return false;
        }
        return isPotentialTargetSimpleName(className);
    }

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

                    SCREEN_CAPTURE_ACTIVE.set(true);
                    log("MediaProjection.createVirtualDisplay " + name + ": " + oldW + "x" + oldH + " density=" + density
                            + " -> " + target[0] + "x" + target[1]);
                    param.args[1] = target[0];
                    param.args[2] = target[1];
                }
            });

            XposedBridge.hookAllMethods(mediaProjection, "stop", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (SCREEN_CAPTURE_ACTIVE.getAndSet(false)) {
                        log("MediaProjection.stop: screen capture inactive");
                    }
                }
            });
            log("Framework fallback installed: MediaProjection.createVirtualDisplay/stop");
        } catch (Throwable t) {
            log("Framework fallback failed: MediaProjection: " + t);
        }

        try {
            XposedBridge.hookAllMethods(MediaCodec.class, "configure", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!SCREEN_CAPTURE_ACTIVE.get() || param.args == null || param.args.length < 4
                            || !(param.args[0] instanceof MediaFormat) || !(param.args[3] instanceof Integer)) {
                        return;
                    }
                    int flags = (Integer) param.args[3];
                    if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) == 0) {
                        return;
                    }

                    MediaFormat format = (MediaFormat) param.args[0];
                    String text = String.valueOf(format);
                    if (isVideoFormat(text)) {
                        log("MediaCodec.configure VIDEO ENCODER while screen capture active: " + text);
                    }
                }
            });

            XposedBridge.hookAllMethods(MediaCodec.class, "setParameters", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!SCREEN_CAPTURE_ACTIVE.get() || param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Bundle)) {
                        return;
                    }
                    log("MediaCodec.setParameters while screen capture active: " + param.args[0]);
                }
            });
            log("Diagnostic hooks installed: MediaCodec.configure/setParameters");
        } catch (Throwable t) {
            log("Diagnostic hook failed: MediaCodec: " + t);
        }
    }

    private static boolean isTikTokScreenCapture(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return SCREEN_CAPTURE_NAME.equals(name)
                || lower.contains("webrtc_screencapture")
                || lower.contains("screen_capture");
    }

    private static boolean isVideoFormat(String format) {
        if (format == null) {
            return false;
        }
        String lower = format.toLowerCase();
        return lower.contains("video/") || lower.contains("mime=video");
    }

    private static void installForClass(Class<?> clazz) {
        if (clazz == null || !isPotentialTargetSimpleName(clazz.getName()) || !INSTALLED.add(clazz)) {
            return;
        }

        try {
            switch (clazz.getSimpleName()) {
                case "RTCScreenProfile":
                    hookScreenProfile(clazz);
                    break;
                case "RTCEngineImpl":
                    hookRtcEngine(clazz);
                    break;
                case "ByteMediaRecorder":
                    hookByteMediaRecorder(clazz);
                    break;
                case "VideoRecorderManager":
                    hookVideoRecorderManager(clazz);
                    break;
                case "ScreenRecorder":
                    hookScreenRecorderLogging(clazz);
                    break;
                case "VideoEncoder":
                    hookVideoEncoderLogging(clazz);
                    break;
                default:
                    INSTALLED.remove(clazz);
                    return;
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
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
