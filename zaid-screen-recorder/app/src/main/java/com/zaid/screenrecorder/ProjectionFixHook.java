package com.zaid.screenrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ProjectionFixHook implements IXposedHookLoadPackage {
    private static final String TAG = "ZaidScreenRecorder";
    private static final int ENTIRE_SCREEN = 1;

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
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGETS.contains(lpparam.packageName)) return;
        log("loaded package=" + lpparam.packageName + " process=" + lpparam.processName);

        installFullDisplayProjectionHook(lpparam.packageName);
        installVirtualDisplayGuard(lpparam.packageName);

        if ("com.android.systemui".equals(lpparam.packageName)) {
            installSystemUiRedirect();
            installSystemPermissionDialogReplacement(lpparam.classLoader);
        }
    }

    private void installFullDisplayProjectionHook(String pkg) {
        try {
            Class<?> configClass = XposedHelpers.findClassIfExists("android.media.projection.MediaProjectionConfig", null);
            if (configClass == null) {
                log(pkg + ": MediaProjectionConfig unavailable; Android < 14 path");
                return;
            }

            Class<?> managerClass = MediaProjectionManager.class;
            Method withConfig = managerClass.getDeclaredMethod("createScreenCaptureIntent", configClass);
            withConfig.setAccessible(true);

            XposedBridge.hookMethod(withConfig, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object full = XposedHelpers.callStaticMethod(configClass, "createConfigForDefaultDisplay");
                        param.args[0] = full;
                        log(pkg + ": force MediaProjectionConfig=DEFAULT_DISPLAY");
                    } catch (Throwable t) {
                        log(pkg + ": force config failed " + t);
                    }
                }
            });

            Method noArgs = managerClass.getDeclaredMethod("createScreenCaptureIntent");
            noArgs.setAccessible(true);
            XposedBridge.hookMethod(noArgs, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object full = XposedHelpers.callStaticMethod(configClass, "createConfigForDefaultDisplay");
                        Object intent = XposedBridge.invokeOriginalMethod(withConfig, param.thisObject, new Object[]{full});
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
            XposedBridge.hookAllMethods(MediaProjection.class, "createVirtualDisplay", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 4) return;
                    if (!(param.args[0] instanceof String) || !(param.args[1] instanceof Integer)
                            || !(param.args[2] instanceof Integer) || !(param.args[3] instanceof Integer)) return;

                    String name = (String) param.args[0];
                    int width = (Integer) param.args[1];
                    int height = (Integer) param.args[2];
                    int dpi = (Integer) param.args[3];
                    int[] real = realDisplay();
                    int fixedW = width;
                    int fixedH = height;

                    // TikTok has its own cast/RTC encoder pipeline and may intentionally use a
                    // scaled VirtualDisplay. Force its consent mode to default-display, but do
                    // not rewrite its encoder dimensions here.
                    if (real[0] > 0 && real[1] > 0 && !"com.zhiliaoapp.musically".equals(pkg)) {
                        if (width * 2 == real[0] && height == real[1]) fixedW = real[0];
                        if (height * 2 == real[1] && width == real[0]) fixedH = real[1];
                        if (width * 2 == real[1] && height == real[0]) fixedW = real[1];
                        if (height * 2 == real[0] && width == real[1]) fixedH = real[0];
                    }

                    if (fixedW != width || fixedH != height) {
                        param.args[1] = fixedW;
                        param.args[2] = fixedH;
                        log(pkg + ": fixed VirtualDisplay " + name + " " + width + "x" + height + " -> " + fixedW + "x" + fixedH + " dpi=" + dpi);
                    } else {
                        log(pkg + ": VirtualDisplay " + name + " " + width + "x" + height + " dpi=" + dpi + " real=" + real[0] + "x" + real[1]);
                    }
                }
            });
            log(pkg + ": VirtualDisplay guard installed");
        } catch (Throwable t) {
            log(pkg + ": VirtualDisplay hook install failed " + t);
        }
    }

    /**
     * Replaces the stock SystemUI MediaProjection permission sheet for the supported clients.
     * The original SystemUI Activity remains responsible for consent, projection creation and
     * returning the IMediaProjection token to the calling app. We only replace the UI that is
     * passed into setUpDialog(), so there is no stock-dialog flash.
     */
    private void installSystemPermissionDialogReplacement(ClassLoader classLoader) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity",
                    classLoader);

            XposedHelpers.findAndHookMethod(activityClass, "setUpDialog", AlertDialog.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;

                            String hostPackage = null;
                            try {
                                Object field = XposedHelpers.getObjectField(activity, "mPackageName");
                                if (field instanceof String) hostPackage = (String) field;
                            } catch (Throwable ignored) {
                            }

                            if (hostPackage == null || !PROJECTION_CLIENTS.contains(hostPackage)) return;

                            AlertDialog replacement = buildProjectionConsentDialog(activity, hostPackage);
                            XposedHelpers.setObjectField(activity, "mDialog", replacement);
                            param.args[0] = replacement;
                            log("SystemUI MediaProjection dialog replaced for " + hostPackage);
                        }
                    });

            log("SystemUI MediaProjection permission-dialog replacement installed");
        } catch (Throwable t) {
            log("SystemUI MediaProjection permission-dialog replacement failed " + t);
        }
    }

    private AlertDialog buildProjectionConsentDialog(Activity activity, String hostPackage) {
        String appName = appLabel(activity, hostPackage);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 26), dp(activity, 24), dp(activity, 26), dp(activity, 16));
        card.setBackground(roundRect(Color.WHITE, dp(activity, 30)));

        ImageView icon = new ImageView(activity);
        try {
            Drawable appIcon = activity.getPackageManager().getApplicationIcon(hostPackage);
            icon.setImageDrawable(appIcon);
        } catch (Throwable ignored) {
            icon.setImageResource(android.R.drawable.ic_menu_camera);
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.bottomMargin = dp(activity, 16);
        card.addView(icon, iconLp);

        TextView brand = text(activity, "Zaid Screen Share", 13, Color.rgb(95, 95, 105));
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.bottomMargin = dp(activity, 6);
        card.addView(brand, brandLp);

        TextView title = text(activity, "Compartir pantalla con " + appName, 24, Color.rgb(18, 18, 20));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(activity, 12);
        card.addView(title, titleLp);

        TextView mode = text(activity, "Pantalla completa  •  sin recorte", 14, Color.rgb(38, 111, 235));
        mode.setGravity(Gravity.CENTER);
        mode.setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 14), dp(activity, 9));
        mode.setBackground(roundRect(Color.rgb(235, 243, 255), dp(activity, 18)));
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.CENTER_HORIZONTAL;
        modeLp.bottomMargin = dp(activity, 18);
        card.addView(mode, modeLp);

        TextView warning = text(activity,
                "Todo lo que aparezca en tu pantalla será visible en " + appName
                        + ". Oculta contraseñas, mensajes, fotos o información privada antes de continuar.",
                14, Color.rgb(90, 90, 98));
        warning.setGravity(Gravity.CENTER);
        warning.setLineSpacing(0f, 1.12f);
        card.addView(warning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(
                activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setView(card)
                .setNegativeButton("Cancelar", (d, which) -> {
                    activity.setResult(Activity.RESULT_CANCELED);
                    activity.finish();
                })
                .setPositiveButton("Compartir pantalla", (d, which) -> {
                    try {
                        XposedHelpers.callMethod(activity, "grantMediaProjectionPermission", ENTIRE_SCREEN);
                        log("custom consent accepted host=" + hostPackage + " mode=ENTIRE_SCREEN");
                    } catch (Throwable t) {
                        log("custom consent grant failed host=" + hostPackage + " error=" + t);
                        activity.setResult(Activity.RESULT_CANCELED);
                        activity.finish();
                    }
                })
                .create();

        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            try {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.setGravity(Gravity.BOTTOM);
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(42, 126, 244));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
                }
                if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(70, 70, 76));
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setFilterTouchesWhenObscured(true);
                }
            } catch (Throwable t) {
                log("custom consent styling failed " + t);
            }
        });

        return dialog;
    }

    private String appLabel(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = info.loadLabel(pm);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
        }
        return packageName;
    }

    private TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void installSystemUiRedirect() {
        try {
            Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", null);
            XC_MethodHook redirect = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        if (!(arg instanceof Intent)) continue;
                        Intent intent = (Intent) arg;
                        String target = intent.getPackage();
                        if (intent.getComponent() != null) target = intent.getComponent().getPackageName();
                        if (!"com.miui.screenrecorder".equals(target)) continue;

                        Intent replacement = new Intent();
                        replacement.setClassName("com.zaid.screenrecorder", "com.zaid.screenrecorder.MainActivity");
                        replacement.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        param.args[i] = replacement;
                        log("SystemUI redirected MIUI Screen Recorder activity -> Zaid Screen Recorder");
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
