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
 * HyperOS 3 MediaProjection consent replacement v1.4.
 *
 * Runtime tracing on duchamp proved an important ordering detail: hooking framework
 * Activity.onCreate() observes the call to super.onCreate(), not the completion of the
 * subclass' MediaProjectionPermissionActivity.onCreate(). v1.3 therefore displayed our
 * dialog first, then HyperOS continued its onCreate(), overwrote mDialog and displayed the
 * stock consent sheet on top.
 *
 * v1.4 uses that early framework callback only to obtain the exact runtime Class object from
 * MiuiSystemUI's real ClassLoader. Before the subclass continues, we dynamically hook its
 * private setUpDialog(...). When HyperOS later creates its normal consent mDialog and calls
 * setUpDialog(mDialog), we swap both the method argument and the Activity mDialog field to
 * Zaid Screen Share. HyperOS then performs its own security setup and its subsequent
 * mDialog.show() naturally shows our replacement. The stock dialog is never shown.
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

    private static final Set<Class<?>> HOOKED_ACTIVITY_CLASSES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Activity> REPLACED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile boolean bootstrapInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        synchronized (SystemUiProjectionDialogHook.class) {
            if (bootstrapInstalled) {
                log("v1.4 bootstrap already installed process=" + lpparam.processName);
                return;
            }
            bootstrapInstalled = true;
        }

        installRuntimeClassBootstrap();
        log("v1.4 pre-show consent replacement active process=" + lpparam.processName);
    }

    /**
     * Activity.onCreate() is reached when the permission Activity calls super.onCreate().
     * At this moment the concrete runtime Class is already available, but the subclass has not
     * yet built/shown its consent dialog. That gives us the exact ClassLoader and enough time to
     * hook the private setUpDialog() before it is called later in the same onCreate().
     */
    private void installRuntimeClassBootstrap() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!PERMISSION_ACTIVITY.equals(activity.getClass().getName())) return;

                    log("v1.4 PERMISSION ACTIVITY ENTER calling=" + activity.getCallingPackage()
                            + " loader=" + activity.getClass().getClassLoader());
                    ensureExactPermissionClassHook(activity.getClass());
                }
            });
            log("v1.4 Activity.onCreate bootstrap installed");
        } catch (Throwable t) {
            log("v1.4 Activity.onCreate bootstrap failed " + t);
        }
    }

    private void ensureExactPermissionClassHook(Class<?> exactClass) {
        synchronized (HOOKED_ACTIVITY_CLASSES) {
            if (!HOOKED_ACTIVITY_CLASSES.add(exactClass)) return;
        }

        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    exactClass,
                    "setUpDialog",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;

                            int dialogIndex = findAlertDialogArgument(param.args);
                            if (dialogIndex < 0) {
                                log("v1.4 setUpDialog hit without AlertDialog argument method="
                                        + param.method);
                                return;
                            }

                            AlertDialog stock = (AlertDialog) param.args[dialogIndex];
                            String host = resolveHostPackage(activity);

                            log("v1.4 setUpDialog HIT host=" + host
                                    + " stock=" + stock.getClass().getName()
                                    + " method=" + param.method);

                            if (host == null || !CLIENTS.contains(host)) {
                                log("v1.4 stock dialog left intact: unsupported/unresolved host");
                                return;
                            }

                            // Device-policy blocked-capture dialogs also pass through setUpDialog().
                            // The normal consent path assigns the dialog to mDialog BEFORE calling
                            // setUpDialog(mDialog). Only replace that exact field-owned dialog so we
                            // never bypass a ScreenCaptureDisabledDialog or other policy warning.
                            Object fieldDialog = null;
                            try {
                                fieldDialog = XposedHelpers.getObjectField(activity, "mDialog");
                            } catch (Throwable t) {
                                log("v1.4 could not inspect mDialog; leaving stock intact " + t);
                                return;
                            }

                            if (fieldDialog != stock) {
                                log("v1.4 setUpDialog skipped because argument is not Activity.mDialog"
                                        + " arg=" + stock.getClass().getName()
                                        + " field=" + (fieldDialog == null ? "null"
                                        : fieldDialog.getClass().getName()));
                                return;
                            }

                            synchronized (REPLACED_ACTIVITIES) {
                                if (!REPLACED_ACTIVITIES.add(activity)) {
                                    log("v1.4 Activity already replaced host=" + host);
                                    return;
                                }
                            }

                            AlertDialog replacement = buildDialog(activity, host);

                            try {
                                XposedHelpers.setObjectField(activity, "mDialog", replacement);
                                param.args[dialogIndex] = replacement;
                                log("v1.4 STOCK CONSENT REPLACED BEFORE SHOW host=" + host
                                        + " old=" + stock.getClass().getName()
                                        + " new=" + replacement.getClass().getName());
                            } catch (Throwable t) {
                                synchronized (REPLACED_ACTIVITIES) {
                                    REPLACED_ACTIVITIES.remove(activity);
                                }
                                log("v1.4 replacement assignment failed " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            synchronized (REPLACED_ACTIVITIES) {
                                if (REPLACED_ACTIVITIES.contains(activity)) {
                                    log("v1.4 replacement passed original setUpDialog; HyperOS next show()"
                                            + " will target Zaid Screen Share");
                                }
                            }
                        }
                    });

            log("v1.4 exact runtime permission class hooked setUpDialog count=" + hooks.size()
                    + " class=" + exactClass
                    + " loader=" + exactClass.getClassLoader());
        } catch (Throwable t) {
            log("v1.4 exact setUpDialog hook failed class=" + exactClass + " error=" + t);
        }
    }

    private int findAlertDialogArgument(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof AlertDialog) return i;
        }
        return -1;
    }

    private AlertDialog buildDialog(Activity activity, String host) {
        String appName = appLabel(activity, host);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 26), dp(activity, 24), dp(activity, 26), dp(activity, 18));
        card.setBackground(roundRect(Color.WHITE, dp(activity, 30)));

        ImageView icon = new ImageView(activity);
        try {
            Drawable drawable = activity.getPackageManager().getApplicationIcon(host);
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
        card.addView(brand, matchWrap());

        TextView title = text(activity,
                "Compartir pantalla con " + appName, 23, Color.rgb(18, 18, 20));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 5), 0, dp(activity, 13));
        card.addView(title, matchWrap());

        TextView mode = text(activity,
                "Pantalla completa  •  sin recorte", 14, Color.rgb(35, 108, 232));
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
        card.addView(warning, matchWrap());

        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(
                activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setView(card)
                .setNegativeButton("Cancelar", (d, which) -> cancel(activity))
                .setPositiveButton("Compartir pantalla", (d, which) ->
                        grantEntireScreen(activity,
                                holder[0] == null ? d : holder[0],
                                host))
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
                log("v1.4 CUSTOM CONSENT VISIBLE host=" + host);
            } catch (Throwable t) {
                log("v1.4 custom consent styling failed " + t);
            }
        });

        return dialog;
    }

    private void grantEntireScreen(Activity activity,
                                   DialogInterface dialog,
                                   String host) {
        boolean casting = hasCastingCapabilities(activity, host);
        log("v1.4 consent positive host=" + host + " casting=" + casting);

        // HyperOS/Android 16 variants may expose either the one- or two-argument private path.
        try {
            XposedHelpers.callMethod(activity,
                    "grantMediaProjectionPermission", ENTIRE_SCREEN, casting);
            log("v1.4 CONSENT GRANTED via grantMediaProjectionPermission(int,boolean) host="
                    + host);
            return;
        } catch (Throwable t) {
            log("v1.4 two-arg grant unavailable " + t);
        }

        try {
            XposedHelpers.callMethod(activity,
                    "grantMediaProjectionPermission", ENTIRE_SCREEN);
            log("v1.4 CONSENT GRANTED via grantMediaProjectionPermission(int) host=" + host);
            return;
        } catch (Throwable t) {
            log("v1.4 one-arg grant unavailable " + t);
        }

        try {
            XposedHelpers.callMethod(activity,
                    "onClick", dialog, AlertDialog.BUTTON_POSITIVE);
            log("v1.4 CONSENT GRANTED via original onClick host=" + host);
            return;
        } catch (Throwable t) {
            log("v1.4 onClick grant unavailable " + t);
        }

        logCandidateMethods(activity);
        cancel(activity);
    }

    private boolean hasCastingCapabilities(Activity activity, String host) {
        try {
            Class<?> utils = XposedHelpers.findClassIfExists(
                    "com.android.systemui.mediaprojection.MediaProjectionUtils",
                    activity.getClass().getClassLoader());
            if (utils != null) {
                Object instance = XposedHelpers.getStaticObjectField(utils, "INSTANCE");
                Object value = XposedHelpers.callMethod(instance,
                        "packageHasCastingCapabilities",
                        activity.getPackageManager(), host);
                if (value instanceof Boolean) {
                    boolean result = (Boolean) value;
                    log("v1.4 casting capability=" + result + " host=" + host);
                    return result;
                }
            }
        } catch (Throwable t) {
            log("v1.4 casting capability reflection unavailable " + t);
        }
        return CASTING_CLIENTS.contains(host);
    }

    private void logCandidateMethods(Activity activity) {
        try {
            for (Method method : activity.getClass().getDeclaredMethods()) {
                String name = method.getName().toLowerCase();
                if (name.contains("grant") || name.contains("projection")
                        || name.contains("click") || name.contains("dialog")) {
                    log("v1.4 candidate method=" + method);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String resolveHostPackage(Activity activity) {
        String[] fields = {
                "mPackageName", "packageName", "mCallingPackage",
                "mHostPackage", "mTargetPackageName"
        };
        for (String field : fields) {
            try {
                Object value = XposedHelpers.getObjectField(activity, field);
                if (value instanceof String && CLIENTS.contains(value)) {
                    return (String) value;
                }
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
                    if (value instanceof String && CLIENTS.contains(value)) {
                        return (String) value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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
