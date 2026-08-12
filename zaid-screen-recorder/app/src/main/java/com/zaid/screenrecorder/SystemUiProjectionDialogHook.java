package com.zaid.screenrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 3 MediaProjection consent replacement.
 *
 * The stock Android 16 SystemUI permission activity deliberately creates its permission
 * dialog with the application context. That is why resolving the Activity from Dialog.show()
 * was unreliable on HyperOS. v1.3 hooks framework Activity.onCreate(), which our runtime logs
 * proved is reached by the real MiuiSystemUI MediaProjectionPermissionActivity, then swaps
 * its already-created mDialog before the first frame is presented.
 *
 * The real SystemUI Activity remains responsible for creating and returning the actual
 * MediaProjection token. This code only replaces the visible consent UI and requests
 * ENTIRE_SCREEN after the user presses the positive button.
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

    private static final Set<String> CASTING_CLIENTS = new HashSet<>(Arrays.asList(
            "com.zhiliaoapp.musically",
            "com.xiaomi.mirror",
            "com.google.android.apps.chromecast.app",
            "com.miui.mishare.connectivity",
            "com.gxdevs.screenx"
    ));

    private static final Set<Activity> REPLACED = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        synchronized (SystemUiProjectionDialogHook.class) {
            if (installed) {
                log("v1.3 Activity consent hook already installed");
                return;
            }
            installed = true;
        }

        installActivityReplacement();
        log("v1.3 Activity.onCreate MediaProjection consent replacement active process="
                + lpparam.processName);
    }

    private void installActivityReplacement() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    String activityName = activity.getClass().getName();
                    if (!PERMISSION_ACTIVITY.equals(activityName)) return;

                    String hostPackage = resolveHostPackage(activity);
                    log("v1.3 ACTIVITY HIT class=" + activityName
                            + " calling=" + activity.getCallingPackage()
                            + " host=" + hostPackage
                            + " loader=" + activity.getClass().getClassLoader());

                    if (hostPackage == null || !CLIENTS.contains(hostPackage)) {
                        log("v1.3 stock consent left intact: unsupported/unresolved host");
                        return;
                    }

                    synchronized (REPLACED) {
                        if (!REPLACED.add(activity)) {
                            log("v1.3 activity already replaced host=" + hostPackage);
                            return;
                        }
                    }

                    replaceStockDialog(activity, hostPackage);
                }
            });
            log("v1.3 Activity.onCreate interceptor installed");
        } catch (Throwable t) {
            log("v1.3 Activity.onCreate interceptor failed " + t);
        }
    }

    private void replaceStockDialog(Activity activity, String hostPackage) {
        Dialog stock = null;
        try {
            Object value = XposedHelpers.getObjectField(activity, "mDialog");
            if (value instanceof Dialog) stock = (Dialog) value;
        } catch (Throwable t) {
            log("v1.3 could not read mDialog " + t);
        }

        if (stock != null) {
            try {
                // SystemUI normally finishes the Activity when this dialog is dismissed.
                // Clear those callbacks before hiding the stock sheet so the Activity remains
                // alive to grant the real MediaProjection token through our replacement UI.
                stock.setOnCancelListener(null);
                stock.setOnDismissListener(null);
                stock.dismiss();
                log("v1.3 STOCK CONSENT DISMISSED host=" + hostPackage
                        + " class=" + stock.getClass().getName());
            } catch (Throwable t) {
                log("v1.3 stock consent dismiss failed " + t);
            }
        } else {
            log("v1.3 mDialog was null/non-Dialog; continuing with replacement");
        }

        AlertDialog replacement = buildDialog(activity, hostPackage);

        // Reuse SystemUI's own dialog setup when possible: hide non-system overlays, apply
        // SystemUI sizing/flags, and preserve its lifecycle safety behavior.
        try {
            XposedHelpers.callMethod(activity, "setUpDialog", replacement);
            log("v1.3 original setUpDialog applied to replacement");
        } catch (Throwable t) {
            log("v1.3 original setUpDialog unavailable; using local safeguards " + t);
            applyLocalDialogSafeguards(replacement);
        }

        try {
            XposedHelpers.setObjectField(activity, "mDialog", replacement);
        } catch (Throwable t) {
            log("v1.3 failed to write replacement into mDialog " + t);
        }

        try {
            replacement.show();
            log("v1.3 CUSTOM CONSENT SHOWN host=" + hostPackage);
        } catch (Throwable t) {
            log("v1.3 custom consent show failed " + t);
            cancel(activity);
        }
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
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.bottomMargin = dp(activity, 14);
        card.addView(icon, iconLp);

        TextView brand = text(activity, "Zaid Screen Share", 13, Color.rgb(96, 96, 106));
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(activity,
                "Compartir pantalla con " + appName,
                23,
                Color.rgb(18, 18, 20));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 5), 0, dp(activity, 13));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView mode = text(activity,
                "Pantalla completa  •  sin recorte",
                14,
                Color.rgb(35, 108, 232));
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
                14,
                Color.rgb(88, 88, 96));
        warning.setGravity(Gravity.CENTER);
        warning.setLineSpacing(0f, 1.12f);
        card.addView(warning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(
                activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setView(card)
                .setNegativeButton("Cancelar", (d, which) -> cancel(activity))
                .setPositiveButton("Compartir pantalla", (d, which) ->
                        grantEntireScreen(activity,
                                holder[0] == null ? d : holder[0],
                                hostPackage))
                .create();
        holder[0] = dialog;

        dialog.setOwnerActivity(activity);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
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
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setTextColor(Color.rgb(42, 126, 244));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setFilterTouchesWhenObscured(true);
                }
                if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setTextColor(Color.rgb(70, 70, 76));
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setFilterTouchesWhenObscured(true);
                }
            } catch (Throwable t) {
                log("v1.3 custom consent styling failed " + t);
            }
        });
        return dialog;
    }

    private void applyLocalDialogSafeguards(AlertDialog dialog) {
        try {
            dialog.create();
            if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
            }
            Window window = dialog.getWindow();
            if (window != null) {
                // SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 0x00080000.
                window.addSystemFlags(0x00080000);
            }
        } catch (Throwable t) {
            log("v1.3 local dialog safeguards failed " + t);
        }
    }

    private void grantEntireScreen(Activity activity,
                                   DialogInterface dialog,
                                   String hostPackage) {
        boolean casting = hasCastingCapabilities(activity, hostPackage);
        log("v1.3 consent positive host=" + hostPackage + " casting=" + casting);

        // Android 16 branches use grantMediaProjectionPermission(int, boolean).
        try {
            XposedHelpers.callMethod(activity,
                    "grantMediaProjectionPermission",
                    ENTIRE_SCREEN,
                    casting);
            log("v1.3 CONSENT GRANTED via grantMediaProjectionPermission(int,boolean) host="
                    + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.3 two-arg grant unavailable " + t);
        }

        // Older branches use a one-argument private method.
        try {
            XposedHelpers.callMethod(activity,
                    "grantMediaProjectionPermission",
                    ENTIRE_SCREEN);
            log("v1.3 CONSENT GRANTED via grantMediaProjectionPermission(int) host="
                    + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.3 one-arg grant unavailable " + t);
        }

        // Older SystemUI builds implement DialogInterface.OnClickListener.
        try {
            XposedHelpers.callMethod(activity,
                    "onClick",
                    dialog,
                    AlertDialog.BUTTON_POSITIVE);
            log("v1.3 CONSENT GRANTED via original onClick host=" + hostPackage);
            return;
        } catch (Throwable t) {
            log("v1.3 onClick grant unavailable " + t);
        }

        logGrantMethods(activity);
        cancel(activity);
    }

    private boolean hasCastingCapabilities(Activity activity, String hostPackage) {
        try {
            ClassLoader loader = activity.getClass().getClassLoader();
            Class<?> utils = XposedHelpers.findClassIfExists(
                    "com.android.systemui.mediaprojection.MediaProjectionUtils", loader);
            if (utils != null) {
                Object instance = XposedHelpers.getStaticObjectField(utils, "INSTANCE");
                Object value = XposedHelpers.callMethod(
                        instance,
                        "packageHasCastingCapabilities",
                        activity.getPackageManager(),
                        hostPackage);
                if (value instanceof Boolean) {
                    boolean result = (Boolean) value;
                    log("v1.3 MediaProjectionUtils casting capability=" + result
                            + " host=" + hostPackage);
                    return result;
                }
            }
        } catch (Throwable t) {
            log("v1.3 casting capability reflection unavailable " + t);
        }
        return CASTING_CLIENTS.contains(hostPackage);
    }

    private void logGrantMethods(Activity activity) {
        try {
            for (Method method : activity.getClass().getDeclaredMethods()) {
                String low = method.getName().toLowerCase();
                if (low.contains("grant") || low.contains("projection") || low.contains("click")) {
                    log("v1.3 candidate method=" + method);
                }
            }
        } catch (Throwable ignored) {
        }
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
