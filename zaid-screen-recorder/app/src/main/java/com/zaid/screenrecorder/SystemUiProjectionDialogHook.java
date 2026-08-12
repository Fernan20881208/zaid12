package com.zaid.screenrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ClassLoader-independent replacement for the SystemUI MediaProjection consent dialog.
 *
 * HyperOS can instantiate MediaProjectionPermissionActivity from a loader that is not the
 * package loader handed to a legacy Xposed entry point. Hooking the Activity class directly is
 * therefore unreliable. Dialog.show() is a framework method shared by every SystemUI loader, so
 * this hook identifies the permission Activity from the dialog context at the last safe moment,
 * suppresses the stock dialog, and shows our own consent UI while leaving the real SystemUI
 * Activity responsible for creating and returning the MediaProjection token.
 */
public class SystemUiProjectionDialogHook implements IXposedHookLoadPackage {
    private static final String TAG = "ZaidScreenRecorder";
    private static final int ENTIRE_SCREEN = 1;
    private static final String PERMISSION_ACTIVITY =
            "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity";

    private static final Set<String> CLIENTS = new HashSet<>(Arrays.asList(
            "com.zhiliaoapp.musically",
            "com.xiaomi.mirror",
            "com.google.android.apps.chromecast.app",
            "com.miui.mishare.connectivity",
            "com.gxdevs.screenx",
            "com.miui.screenrecorder"
    ));

    private static final ThreadLocal<Boolean> SHOWING_REPLACEMENT = new ThreadLocal<>();
    private static volatile boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        synchronized (SystemUiProjectionDialogHook.class) {
            if (installed) {
                log("v1.2 framework consent hook already installed");
                return;
            }
            installed = true;
        }

        installFrameworkDialogInterceptor();
        installActivityDiscoveryLogger();
        log("v1.2 classloader-independent SystemUI consent hook active process=" + lpparam.processName);
    }

    private void installFrameworkDialogInterceptor() {
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(SHOWING_REPLACEMENT.get())) return;
                    if (!(param.thisObject instanceof Dialog)) return;

                    Dialog stock = (Dialog) param.thisObject;
                    Activity activity = resolveActivity(stock);
                    if (activity == null) return;

                    String activityName = activity.getClass().getName();
                    if (!PERMISSION_ACTIVITY.equals(activityName)) return;

                    String hostPackage = resolveHostPackage(activity);
                    log("v1.2 MediaProjection Dialog.show hit activity=" + activityName
                            + " host=" + hostPackage
                            + " activityLoader=" + activity.getClass().getClassLoader());

                    if (hostPackage == null || !CLIENTS.contains(hostPackage)) {
                        log("v1.2 stock consent left untouched because host is unsupported/unresolved");
                        return;
                    }

                    AlertDialog replacement = buildDialog(activity, hostPackage);
                    try {
                        XposedHelpers.setObjectField(activity, "mDialog", replacement);
                    } catch (Throwable t) {
                        log("v1.2 mDialog field replacement skipped: " + t);
                    }

                    try {
                        SHOWING_REPLACEMENT.set(Boolean.TRUE);
                        replacement.show();
                        log("v1.2 CUSTOM CONSENT SHOWN host=" + hostPackage);
                    } catch (Throwable t) {
                        log("v1.2 custom consent show failed " + t);
                        return;
                    } finally {
                        SHOWING_REPLACEMENT.remove();
                    }

                    // Prevent the original HyperOS/AOSP permission sheet from ever becoming visible.
                    param.setResult(null);
                    log("v1.2 STOCK CONSENT SUPPRESSED host=" + hostPackage);
                }
            });
            log("v1.2 framework Dialog.show interceptor installed");
        } catch (Throwable t) {
            log("v1.2 framework Dialog.show interceptor failed " + t);
        }
    }

    private void installActivityDiscoveryLogger() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    String name = activity.getClass().getName();
                    if (!PERMISSION_ACTIVITY.equals(name)) return;
                    log("v1.2 ACTIVITY DISCOVERY class=" + name
                            + " calling=" + activity.getCallingPackage()
                            + " host=" + resolveHostPackage(activity)
                            + " loader=" + activity.getClass().getClassLoader());
                }
            });
            log("v1.2 MediaProjection Activity discovery logger installed");
        } catch (Throwable t) {
            log("v1.2 Activity discovery logger failed " + t);
        }
    }

    private Activity resolveActivity(Dialog dialog) {
        try {
            Activity owner = dialog.getOwnerActivity();
            if (owner != null) return owner;
        } catch (Throwable ignored) {
        }

        Context context;
        try {
            context = dialog.getContext();
        } catch (Throwable t) {
            return null;
        }

        int depth = 0;
        while (context != null && depth++ < 12) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private String resolveHostPackage(Activity activity) {
        String[] fieldNames = {
                "mPackageName", "packageName", "mCallingPackage",
                "mHostPackage", "mTargetPackageName"
        };
        for (String field : fieldNames) {
            try {
                Object value = XposedHelpers.getObjectField(activity, field);
                if (value instanceof String && CLIENTS.contains(value)) return (String) value;
            } catch (Throwable ignored) {
            }
        }

        try {
            String calling = activity.getCallingPackage();
            if (calling != null && CLIENTS.contains(calling)) return calling;
        } catch (Throwable ignored) {
        }

        try {
            Object launched = XposedHelpers.callMethod(activity, "getLaunchedFromPackage");
            if (launched instanceof String && CLIENTS.contains(launched)) return (String) launched;
        } catch (Throwable ignored) {
        }

        try {
            Intent intent = activity.getIntent();
            Bundle extras = intent == null ? null : intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value instanceof String && CLIENTS.contains(value)) return (String) value;
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

        AlertDialog dialog = new AlertDialog.Builder(
                activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setView(card)
                .setNegativeButton("Cancelar", (d, which) -> cancel(activity))
                .setPositiveButton("Compartir pantalla", (d, which) ->
                        grantEntireScreen(activity, hostPackage))
                .create();

        dialog.setOwnerActivity(activity);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> cancel(activity));
        dialog.setOnShowListener(d -> {
            try {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.setGravity(Gravity.BOTTOM);
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
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
                log("v1.2 custom consent styling failed " + t);
            }
        });
        return dialog;
    }

    private void grantEntireScreen(Activity activity, String hostPackage) {
        try {
            XposedHelpers.callMethod(activity, "grantMediaProjectionPermission", ENTIRE_SCREEN);
            log("v1.2 custom consent accepted via grantMediaProjectionPermission(int) host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.2 one-arg grant unavailable " + t);
        }

        try {
            XposedHelpers.callMethod(activity, "grantMediaProjectionPermission", ENTIRE_SCREEN, false);
            log("v1.2 custom consent accepted via grantMediaProjectionPermission(int,boolean) host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.2 two-arg grant unavailable " + t);
        }

        try {
            XposedHelpers.callMethod(activity, "onClick", null, AlertDialog.BUTTON_POSITIVE);
            log("v1.2 custom consent accepted via original onClick host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.2 all grant paths failed " + t);
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
