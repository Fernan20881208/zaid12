package com.zaid.infinixlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String FREE_FIRE = "com.dts.freefireth";
    private static final String FREE_FIRE_MAX = "com.dts.freefiremax";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        int pad = dp(20);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Infinix X6891 Launcher");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView warning = new TextView(this);
        warning.setText(
                "\nEste launcher no modifica Free Fire ni falsifica la identidad " +
                "del dispositivo dentro del juego. Solo abre la instalación original.\n"
        );
        warning.setTextSize(15);
        root.addView(warning, matchWrap());

        Button ff = button("Abrir Free Fire");
        ff.setOnClickListener(v -> launchPackage(FREE_FIRE));
        root.addView(ff, matchWrap());

        Button ffMax = button("Abrir Free Fire MAX");
        ffMax.setOnClickListener(v -> launchPackage(FREE_FIRE_MAX));
        root.addView(ffMax, matchWrap());

        Button appInfo = button("Información de Free Fire");
        appInfo.setOnClickListener(v -> openAppInfo(FREE_FIRE));
        root.addView(appInfo, matchWrap());

        TextView profileTitle = new TextView(this);
        profileTitle.setText("\nPerfil de referencia");
        profileTitle.setTextSize(20);
        profileTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(profileTitle, matchWrap());

        TextView profile = new TextView(this);
        profile.setText(DeviceProfile.asText());
        profile.setTextSize(14);
        profile.setTextIsSelectable(true);
        profile.setPadding(0, dp(8), 0, dp(24));
        root.addView(profile, matchWrap());

        scroll.addView(root);
        return scroll;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void launchPackage(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Toast.makeText(
                    this,
                    "No está instalada: " + packageName,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openAppInfo(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + packageName));
        try {
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(
                    this,
                    "No se pudo abrir la información de la aplicación.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
