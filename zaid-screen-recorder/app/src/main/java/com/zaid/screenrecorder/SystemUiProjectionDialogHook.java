package com.zaid.screenrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
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

/**
 * HyperOS/AOSP compatibility hook for the MediaProjection consent activity.
 *
 * HyperOS branches can carry either the older AOSP package
 * com.android.systemui.media.MediaProjectionPermissionActivity or the newer
 * com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity.
 * This hook supports both and logs the actual activity class seen at runtime.
 */
public class SystemUiProjectionDialogHook implements IXposedHookLoadPackage {
    private static final String TAG = "ZaidScreenRecorder";
    private static final int ENTIRE_SCREEN = 1;

    private static final String[] ACTIVITY_CANDIDATES = {
            "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity",
            "com.android.systemui.media.MediaProjectionPermissionActivity",
            "com.android.systemui.screenrecord.MediaProjectionPermissionActivity"
    };

    private static final Set<String> CLIENTS = new HashSet<>(Arrays.asList(
            "com.zhiliaoapp.musically",
            "com.xiaomi.mirror",
            "com.google.android.apps.chromecast.app",
            "com.miui.mishare.connectivity",
            "com.gxdevs.screenx",
            "com.miui.screenrecorder"
    ));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        log("v1.2 SystemUI projection-dialog compatibility hook active process=" + lpparam.processName);

        int found = 0;
        for (String candidate : ACTIVITY_CANDIDATES) {
            Class<?> cls = XposedHelpers.findClassIfExists(candidate, lpparam.classLoader);
            if (cls == null) {
                log("v1.2 permission activity NOT found: " + candidate);
                continue;
            }
            found++;
            log("v1.2 permission activity FOUND: " + candidate);
            hookPermissionActivity(cls, candidate);
        }

        installActivityDiscoveryLogger();
        log("v1.2 permission activity candidates found=" + found);
    }

    private void hookPermissionActivity(Class<?> activityClass, String className) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    activityClass,
                    "setUpDialog",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            String hostPackage = resolveHostPackage(activity);

                            log("v1.2 setUpDialog hit class=" + className + " host=" + hostPackage
                                    + " method=" + param.method);

                            if (hostPackage == null || !CLIENTS.contains(hostPackage)) return;

                            int dialogArg = findDialogArg(param.args);
                            if (dialogArg < 0) {
                                log("v1.2 setUpDialog has no AlertDialog argument; cannot replace safely");
                                return;
                            }

                            AlertDialog replacement = buildDialog(activity, hostPackage);

                            try {
                                XposedHelpers.setObjectField(activity, "mDialog", replacement);
                            } catch (Throwable t) {
                                log("v1.2 mDialog field update skipped: " + t);
                            }

                            param.args[dialogArg] = replacement;
                            log("v1.2 CUSTOM DIALOG INSTALLED host=" + hostPackage
                                    + " activity=" + className);
                        }
                    });

            log("v1.2 hooked " + hooks.size() + " setUpDialog method(s) on " + className);
        } catch (Throwable t) {
            log("v1.2 setUpDialog hook failed class=" + className + " error=" + t);
        }
    }

    /**
     * Diagnostic fallback. If Xiaomi renamed the class, this reveals the actual Activity name
     * as soon as its onCreate reaches Activity.onCreate().
     */
    private void installActivityDiscoveryLogger() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    String name = activity.getClass().getName();
                    String low = name.toLowerCase();
                    if (low.contains("projection") || low.contains("capture")
                            || low.contains("screenshare") || low.contains("screen_share")) {
                        log("v1.2 ACTIVITY DISCOVERY class=" + name
                                + " calling=" + activity.getCallingPackage()
                                + " host=" + resolveHostPackage(activity));
                    }
                }
            });
            log("v1.2 Activity discovery logger installed");
        } catch (Throwable t) {
            log("v1.2 Activity discovery logger failed " + t);
        }
    }

    private int findDialogArg(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof AlertDialog) return i;
        }
        return -1;
    }

    private String resolveHostPackage(Activity activity) {
        String[] fieldNames = {
                "mPackageName", "packageName", "mCallingPackage",
                "mHostPackage", "mTargetPackageName"
        };
        for (String field : fieldNames) {
            try {
                Object value = XposedHelpers.getObjectField(activity, field);
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            String calling = activity.getCallingPackage();
            if (calling != null && !calling.isEmpty()) return calling;
        } catch (Throwable ignored) {
        }

        try {
            Intent intent = activity.getIntent();
            Bundle extras = intent == null ? null : intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value instanceof String && CLIENTS.contains(value)) {
                        return (String) value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private AlertDialog buildDialog(Activity activity, String hostPackage) {
        String appName = appLabel(activity, hostPackage);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 26), dp(activity, 24), dp(activity, 26), dp(activity, 18));
        card.setBackground(roundRect(Color.WHITE, dp(activity, 30)));

        ImageView icon = new ImageView(activity);
        try {
            Drawable drawable = activity.getPackageManager().getApplicationIcon(hostPackage);
            icon.setImageDrawable(drawable);
        } catch (Throwable ignored) {
            icon.setImageResource(android.R.drawable.ic_menu_camera);
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.bottomMargin = dp(activity, 14);
        card.addView(icon, iconLp);

        TextView brand = text(activity, "Zaid Screen Share", 13, Color.rgb(96, 96, 106));
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(activity, "Compartir pantalla con " + appName, 23, Color.rgb(18, 18, 20));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 5), 0, dp(activity, 13));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView mode = text(activity, "Pantalla completa  •  sin recorte", 14, Color.rgb(35, 108, 232));
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
                        + ". Oculta contraseñas, mensajes, fotos y otra información privada antes de continuar.",
                14, Color.rgb(88, 88, 96));
        warning.setGravity(Gravity.CENTER);
        warning.setLineSpacing(0f, 1.12f);
        card.addView(warning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(
                activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setView(card)
                .setNegativeButton("Cancelar", (d, which) -> cancel(activity))
                .setPositiveButton("Compartir pantalla", (d, which) -> {
                    AlertDialog current = holder[0];
                    grantEntireScreen(activity, current == null ? d : current, hostPackage);
                })
                .create();
        holder[0] = dialog;

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
                log("v1.2 custom dialog styling failed " + t);
            }
        });

        return dialog;
    }

    private void grantEntireScreen(Activity activity, DialogInterface dialog, String hostPackage) {
        // Older AOSP/MIUI branches implement DialogInterface.OnClickListener and already map
        // positive-button clicks to ENTIRE_SCREEN. Prefer that original path when available.
        try {
            XposedHelpers.callMethod(activity, "onClick", dialog, AlertDialog.BUTTON_POSITIVE);
            log("v1.2 custom consent accepted through original onClick host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.2 original onClick grant path unavailable: " + t);
        }

        // Fallback for branches where grantMediaProjectionPermission(int) remains private.
        try {
            XposedHelpers.callMethod(activity, "grantMediaProjectionPermission", ENTIRE_SCREEN);
            log("v1.2 custom consent accepted through grantMediaProjectionPermission(int) host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.2 one-arg grant path unavailable: " + t);
        }

        // Newer AOSP branches may carry a second casting-capabilities boolean.
        try {
            XposedHelpers.callMethod(activity, "grantMediaProjectionPermission", ENTIRE_SCREEN, false);
            log("v1.2 custom consent accepted through grantMediaProjectionPermission(int,boolean) host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.2 two-arg grant path failed: " + t);
        }

        cancel(activity);
    }

    private void cancel(Activity activity) {
        try {
            activity.setResult(Activity.RESULT_CANCELED);
        } catch (Throwable ignored) {
        }
        try {
            activity.finish();
        } catch (Throwable ignored) {
        }
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

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
