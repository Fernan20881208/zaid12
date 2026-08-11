package com.zaid.tiktoklivequality;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
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
 * v1.4 direct DEX bridge for TikTok's df_live_cast feature.
 *
 * TikTok ships the Java bytecode for this feature inside a ZIP container named
 * libdex_df_live_cast.so under nativeLibraryDir. The corresponding split APK has
 * no classes.dex, so waiting for split APK class loaders cannot discover these
 * classes. This bridge adds the real libdex container to TikTok's main app
 * ClassLoader and immediately installs the existing package-scoped hooks on the
 * resolved com.byted.cast classes.
 */
public final class TikTokLiveDexInjectorHook implements IXposedHookLoadPackage {
    private static final String TAG = "TikTokLiveQuality";
    private static final String TARGET_PACKAGE = "com.zhiliaoapp.musically";
    private static final String LIVE_CAST_DEX = "libdex_df_live_cast.so";

    private static final Set<String> TARGET_CLASSES = new HashSet<>(Arrays.asList(
            "com.byted.cast.sdk.RTCScreenProfile",
            "com.byted.cast.sdk.core.RTCEngineImpl",
            "com.byted.cast.capture.ByteMediaRecorder",
            "com.byted.cast.capture.video.VideoRecorderManager",
            "com.byted.cast.capture.video.screen.ScreenRecorder",
            "com.byted.cast.capture.encoder.VideoEncoder"
    ));

    private static final Set<ClassLoader> INJECTED_LOADERS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("v1.4 dex injector active process=" + lpparam.processName
                + " loader=" + describe(lpparam.classLoader));

        inject(lpparam.appInfo, lpparam.classLoader, "handleLoadPackage");
        hookApplicationAttach();
    }

    private static void hookApplicationAttach() {
        if (!ATTACH_HOOKED.compareAndSet(false, true)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof Context)) {
                                return;
                            }

                            Context context = (Context) param.args[0];
                            if (!TARGET_PACKAGE.equals(context.getPackageName())) {
                                return;
                            }

                            log("v1.4 Application.attach context loader="
                                    + describe(context.getClassLoader()));
                            inject(context.getApplicationInfo(), context.getClassLoader(),
                                    "Application.attach");
                        }
                    });
            log("v1.4 hook installed: Application.attach");
        } catch (Throwable t) {
            log("v1.4 Application.attach hook failed: " + t);
        }
    }

    private static void inject(ApplicationInfo appInfo, ClassLoader loader, String source) {
        if (appInfo == null || loader == null) {
            log("v1.4 inject skipped via " + source + ": appInfo/loader null");
            return;
        }

        String nativeDir = appInfo.nativeLibraryDir;
        if (nativeDir == null || nativeDir.isEmpty()) {
            log("v1.4 nativeLibraryDir unavailable via " + source);
            return;
        }

        File dexContainer = new File(nativeDir, LIVE_CAST_DEX);
        String path = dexContainer.getAbsolutePath();
        log("LIVE_CAST DEX path=" + path + " exists=" + dexContainer.exists()
                + " size=" + (dexContainer.exists() ? dexContainer.length() : -1));

        if (!dexContainer.isFile()) {
            log("v1.4 DEX container missing via " + source);
            return;
        }

        // If this loader already resolves the target classes, hook them without mutating it.
        int preloaded = loadAndHandoff(loader, "v1.4 pre-inject/" + source);
        if (preloaded == TARGET_CLASSES.size()) {
            log("v1.4 all targets already visible; addDexPath not needed");
            INJECTED_LOADERS.add(loader);
            return;
        }

        if (!INJECTED_LOADERS.add(loader)) {
            log("v1.4 loader already attempted: " + describe(loader));
            loadAndHandoff(loader, "v1.4 repeat/" + source);
            return;
        }

        enumerateContainer(path, source);

        boolean added = false;
        Throwable firstError = null;
        try {
            XposedHelpers.callMethod(loader, "addDexPath", path);
            added = true;
            log("v1.4 addDexPath(String) success loader=" + describe(loader));
        } catch (Throwable t) {
            firstError = t;
            log("v1.4 addDexPath(String) failed: " + shortThrowable(t));
        }

        if (!added) {
            try {
                XposedHelpers.callMethod(loader, "addDexPath", path, false);
                added = true;
                log("v1.4 addDexPath(String,boolean) success loader=" + describe(loader));
            } catch (Throwable t) {
                log("v1.4 addDexPath(String,boolean) failed: " + shortThrowable(t)
                        + (firstError == null ? "" : " first=" + shortThrowable(firstError)));
            }
        }

        if (!added) {
            return;
        }

        try {
            XposedHelpers.callStaticMethod(TikTokLiveQualityHook.class,
                    "inspectLoader", loader, "v1.4 direct dex/" + source, true);
        } catch (Throwable t) {
            log("v1.4 inspectLoader bridge failed: " + shortThrowable(t));
        }

        int loaded = loadAndHandoff(loader, "v1.4 post-inject/" + source);
        log("v1.4 post-inject target count=" + loaded + "/" + TARGET_CLASSES.size());
    }

    private static void enumerateContainer(String path, String source) {
        DexFile dexFile = null;
        try {
            dexFile = new DexFile(path);
            int scanned = 0;
            int matched = 0;
            Enumeration<String> entries = dexFile.entries();
            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                scanned++;
                if (TARGET_CLASSES.contains(className)) {
                    matched++;
                    log("LIVE_CAST DEX candidate=" + className);
                }
            }
            log("v1.4 DEX enumeration via " + source + " scanned=" + scanned
                    + " matched=" + matched);
        } catch (Throwable t) {
            log("v1.4 DEX enumeration failed path=" + path + ": " + shortThrowable(t));
        } finally {
            if (dexFile != null) {
                try {
                    dexFile.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int loadAndHandoff(ClassLoader loader, String source) {
        int loaded = 0;
        for (String className : TARGET_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className, false, loader);
                loaded++;
                log("DYNDEX LOADED TARGET " + className + " via " + source
                        + " classLoader=" + describe(clazz.getClassLoader()));
                try {
                    XposedHelpers.callStaticMethod(TikTokLiveQualityHook.class,
                            "installForClass", clazz);
                    log("DYNDEX HANDOFF COMPLETE " + className);
                } catch (Throwable t) {
                    log("DYNDEX HANDOFF FAILED " + className + ": " + shortThrowable(t));
                }
            } catch (Throwable ignored) {
                // Expected before the live_cast DEX is attached.
            }
        }
        return loaded;
    }

    private static String shortThrowable(Throwable t) {
        if (t == null) {
            return "<null>";
        }
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : ": " + message);
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
