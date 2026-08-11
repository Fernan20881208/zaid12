package com.zaid.tiktoklivequality;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * v1.3 split-aware discovery bridge.
 *
 * TikTok's df_live_cast feature can already be present in ApplicationInfo before the
 * module starts, so waiting for BaseDexClassLoader.addDexPath is not sufficient. This
 * hook resolves the installed split directly, obtains its real split ClassLoader through
 * Context.createContextForSplit / LoadedApk.getSplitClassLoader, enumerates the split DEX,
 * and hands the real runtime Class objects back to TikTokLiveQualityHook.
 */
public final class TikTokLiveCastSplitHook implements IXposedHookLoadPackage {
    private static final String TAG = "TikTokLiveQuality";
    private static final String TARGET_PACKAGE = "com.zhiliaoapp.musically";

    private static final Set<String> TARGET_SIMPLE_NAMES = new HashSet<>(Arrays.asList(
            "RTCScreenProfile",
            "RTCEngineImpl",
            "ByteMediaRecorder",
            "VideoRecorderManager",
            "ScreenRecorder",
            "VideoEncoder"
    ));

    private static final Set<String> ENUMERATED_PATHS = ConcurrentHashMap.newKeySet();
    private static final Set<ClassLoader> INSPECTED_SPLIT_LOADERS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean APP_ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_SPLIT_HOOKS = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("v1.3 split discovery active process=" + lpparam.processName
                + " mainLoader=" + describe(lpparam.classLoader));

        inspectApplicationInfo(lpparam.appInfo, lpparam.classLoader, null, "handleLoadPackage");
        hookApplicationAttach();
        hookFrameworkSplitLoaders();
    }

    private static void hookApplicationAttach() {
        if (!APP_ATTACH_HOOKED.compareAndSet(false, true)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof Context)) {
                        return;
                    }
                    Context context = (Context) param.args[0];
                    if (!TARGET_PACKAGE.equals(context.getPackageName())) {
                        return;
                    }

                    log("Application.attach captured context loader=" + describe(context.getClassLoader()));
                    scanContextSplits(context, "Application.attach");

                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(() -> scanContextSplits(context, "Application.attach+2s"), 2000L);
                    handler.postDelayed(() -> scanContextSplits(context, "Application.attach+8s"), 8000L);
                }
            });
            log("v1.3 hook installed: Application.attach");
        } catch (Throwable t) {
            log("v1.3 Application.attach hook failed: " + t);
        }
    }

    private static void hookFrameworkSplitLoaders() {
        if (!FRAMEWORK_SPLIT_HOOKS.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> contextImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", null);
            if (contextImpl != null) {
                XposedBridge.hookAllMethods(contextImpl, "createContextForSplit", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String splitName = firstStringArg(param.args);
                        if (!isLiveCast(splitName) || param.hasThrowable()) {
                            return;
                        }
                        Object result = param.getResult();
                        if (result instanceof Context) {
                            Context splitContext = (Context) result;
                            ClassLoader loader = splitContext.getClassLoader();
                            log("ContextImpl.createContextForSplit(" + splitName + ") -> " + describe(loader));
                            inspectRealSplitLoader(loader, "ContextImpl.createContextForSplit(" + splitName + ")");
                        }
                    }
                });
                log("v1.3 hook installed: ContextImpl.createContextForSplit");
            }
        } catch (Throwable t) {
            log("v1.3 ContextImpl split hook failed: " + t);
        }

        try {
            Class<?> loadedApk = XposedHelpers.findClassIfExists("android.app.LoadedApk", null);
            if (loadedApk != null) {
                XposedBridge.hookAllMethods(loadedApk, "getSplitClassLoader", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String splitName = firstStringArg(param.args);
                        if (!isLiveCast(splitName) || param.hasThrowable()) {
                            return;
                        }
                        Object result = param.getResult();
                        if (result instanceof ClassLoader) {
                            ClassLoader loader = (ClassLoader) result;
                            log("LoadedApk.getSplitClassLoader(" + splitName + ") -> " + describe(loader));
                            inspectRealSplitLoader(loader, "LoadedApk.getSplitClassLoader(" + splitName + ")");
                        }
                    }
                });
                log("v1.3 hook installed: LoadedApk.getSplitClassLoader");
            }
        } catch (Throwable t) {
            log("v1.3 LoadedApk split hook failed: " + t);
        }
    }

    private static void scanContextSplits(Context context, String source) {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            inspectApplicationInfo(info, context.getClassLoader(), context, source);
        } catch (Throwable t) {
            log("scanContextSplits failed via " + source + ": " + t);
        }
    }

    private static void inspectApplicationInfo(ApplicationInfo info, ClassLoader mainLoader,
                                               Context context, String source) {
        if (info == null) {
            log("ApplicationInfo unavailable via " + source);
            return;
        }

        String[] names = info.splitNames;
        String[] paths = info.splitSourceDirs;
        log("Split inventory via " + source + " names=" + Arrays.toString(names));

        if (paths == null || paths.length == 0) {
            return;
        }

        for (int i = 0; i < paths.length; i++) {
            String path = paths[i];
            String splitName = names != null && i < names.length ? names[i] : null;
            if (!isLiveCast(path) && !isLiveCast(splitName)) {
                continue;
            }

            log("FOUND LIVE_CAST SPLIT via " + source + " name=" + splitName + " path=" + path);

            // Enumerate the APK directly even if the runtime split loader is isolated.
            Set<String> candidates = enumerateSplitPath(path, source);

            // First try the normal app loader; some TikTok builds merge split dex into it.
            loadCandidates(mainLoader, candidates, source + "/mainLoader");

            // Then request Android's actual split context/loader. This is the important v1.3 path.
            if (context != null && splitName != null) {
                try {
                    Context splitContext = context.createContextForSplit(splitName);
                    ClassLoader splitLoader = splitContext.getClassLoader();
                    log("createContextForSplit(" + splitName + ") loader=" + describe(splitLoader));
                    inspectRealSplitLoader(splitLoader, source + "/createContextForSplit(" + splitName + ")");
                    loadCandidates(splitLoader, candidates, source + "/splitLoader(" + splitName + ")");
                } catch (Throwable t) {
                    log("createContextForSplit(" + splitName + ") failed: " + t);
                }
            }
        }
    }

    private static Set<String> enumerateSplitPath(String path, String source) {
        Set<String> candidates = new HashSet<>();
        if (path == null) {
            return candidates;
        }

        boolean firstScan = ENUMERATED_PATHS.add(path);
        try (DexFile dexFile = new DexFile(path)) {
            int scanned = 0;
            Enumeration<String> entries = dexFile.entries();
            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                scanned++;
                if (isTargetCandidate(className)) {
                    candidates.add(className);
                    log("SPLIT TARGET CANDIDATE " + className + " path=" + path);
                }
            }
            if (firstScan) {
                log("Direct split enumeration via " + source + " scanned=" + scanned
                        + " candidates=" + candidates.size() + " path=" + path);
            }
        } catch (Throwable t) {
            log("Direct split enumeration failed path=" + path + ": " + t);
        }
        return candidates;
    }

    private static void inspectRealSplitLoader(ClassLoader loader, String source) {
        if (loader == null) {
            return;
        }

        if (INSPECTED_SPLIT_LOADERS.add(loader)) {
            log("REAL LIVE_CAST LOADER via " + source + " = " + describe(loader));
        }

        try {
            XposedHelpers.callStaticMethod(TikTokLiveQualityHook.class,
                    "inspectLoader", loader, source, true);
        } catch (Throwable t) {
            log("Bridge inspectLoader failed via " + source + ": " + t);
        }
    }

    private static void loadCandidates(ClassLoader loader, Set<String> candidates, String source) {
        if (loader == null || candidates == null || candidates.isEmpty()) {
            return;
        }

        for (String className : candidates) {
            try {
                Class<?> clazz = Class.forName(className, false, loader);
                log("LOADED SPLIT TARGET " + className + " via " + source
                        + " loader=" + describe(clazz.getClassLoader()));
                handOff(clazz, source);
            } catch (Throwable t) {
                log("Could not load " + className + " via " + source + ": "
                        + t.getClass().getSimpleName());
            }
        }
    }

    private static void handOff(Class<?> clazz, String source) {
        try {
            XposedHelpers.callStaticMethod(TikTokLiveQualityHook.class, "installForClass", clazz);
            log("HANDOFF COMPLETE " + clazz.getName() + " via " + source);
        } catch (Throwable t) {
            log("HANDOFF FAILED " + clazz.getName() + " via " + source + ": " + t);
        }
    }

    private static boolean isTargetCandidate(String className) {
        if (className == null) {
            return false;
        }
        int dot = className.lastIndexOf('.');
        String simple = dot >= 0 ? className.substring(dot + 1) : className;
        return TARGET_SIMPLE_NAMES.contains(simple);
    }

    private static String firstStringArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                return (String) arg;
            }
        }
        return null;
    }

    private static boolean isLiveCast(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("live_cast") || lower.contains("livecast") || lower.contains("df_live_cast");
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) {
            return "<boot>";
        }
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void log(String message) {
        String text = TAG + ": " + message;
        XposedBridge.log(text);
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
