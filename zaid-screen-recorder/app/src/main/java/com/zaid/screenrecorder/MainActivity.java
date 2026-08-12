package com.zaid.screenrecorder;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;
    private Button start;
    private Button stop;
    private Spinner bitrate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 12, 14));
        getWindow().setNavigationBarColor(Color.rgb(12, 12, 14));
        setContentView(buildUi());
        refreshAsync();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(12, 12, 14));

        TextView title = text("Zaid Screen Recorder", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("Motor AOSP root + MediaProjection Fix", 14, Color.rgb(165,165,175));
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        status = text("Comprobando…", 16, Color.WHITE);
        status.setPadding(dp(16), dp(15), dp(16), dp(15));
        status.setBackground(roundRect(Color.rgb(29,29,34), 18));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView engine = text("Grabación manual", 18, Color.WHITE);
        engine.setTypeface(null, android.graphics.Typeface.BOLD);
        engine.setPadding(0, dp(24), 0, dp(8));
        root.addView(engine);

        TextView info = text("Usa /system/bin/screenrecord directamente. No pasa por la grabadora rota de HyperOS. El tamaño queda automático para usar la pantalla completa real.", 14, Color.rgb(190,190,200));
        info.setPadding(0, 0, 0, dp(14));
        root.addView(info);

        bitrate = new Spinner(this);
        String[] rates = {"20 Mbps · Equilibrado", "30 Mbps · Alta calidad", "50 Mbps · Máxima calidad"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rates);
        bitrate.setAdapter(adapter);
        bitrate.setSelection(1);
        root.addView(bitrate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        start = button("●  Iniciar grabación");
        start.setOnClickListener(v -> startRecording());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        bp.topMargin = dp(14);
        root.addView(start, bp);

        stop = button("■  Detener y guardar");
        stop.setOnClickListener(v -> stopRecording());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        sp.topMargin = dp(10);
        root.addView(stop, sp);

        Button addTile = button("Añadir a Ajustes rápidos");
        addTile.setOnClickListener(v -> requestTile());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        tp.topMargin = dp(18);
        root.addView(addTile, tp);

        TextView projection = text("MediaProjection Fix", 18, Color.WHITE);
        projection.setTypeface(null, android.graphics.Typeface.BOLD);
        projection.setPadding(0, dp(26), 0, dp(8));
        root.addView(projection);

        TextView projectionInfo = text("Al habilitar este APK como módulo LSPosed, TikTok LIVE y Xiaomi Mirror conservan el permiso oficial de Android, pero las solicitudes se fuerzan a captura de pantalla completa. También corrige VirtualDisplay si detecta exactamente media dimensión.", 14, Color.rgb(190,190,200));
        root.addView(projectionInfo);

        TextView scope = text("Ámbito recomendado LSPosed:\n• TikTok\n• Xiaomi Mirror\n• SystemUI\n• ScreenX\n• MIUI Screen Recorder", 13, Color.rgb(135,205,255));
        scope.setPadding(0, dp(12), 0, 0);
        root.addView(scope);

        return root;
    }

    private void startRecording() {
        int mbps = bitrate.getSelectedItemPosition() == 0 ? 20 : bitrate.getSelectedItemPosition() == 2 ? 50 : 30;
        status.setText("Iniciando…");
        new Thread(() -> {
            try {
                String file = RootRecorder.start(mbps);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Grabando", Toast.LENGTH_SHORT).show();
                    status.setText("● Grabando\n" + file);
                    setButtons(true);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText("Error al iniciar:\n" + t.getMessage());
                    setButtons(false);
                });
            }
        }).start();
    }

    private void stopRecording() {
        status.setText("Deteniendo y cerrando MP4…");
        new Thread(() -> {
            try {
                RootRecorder.stop();
                String file = RootRecorder.lastOutput();
                runOnUiThread(() -> {
                    status.setText("Guardado\n" + (file.isEmpty() ? "/sdcard/Movies/ZaidScreenRecorder/" : file));
                    setButtons(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Error al detener:\n" + t.getMessage()));
            }
        }).start();
    }

    private void refreshAsync() {
        new Thread(() -> {
            boolean root = RootShell.hasRoot();
            boolean recording = root && RootRecorder.isRecording();
            String last = root ? RootRecorder.lastOutput() : "";
            runOnUiThread(() -> {
                if (!root) status.setText("Root no concedido. Pulsa Iniciar y concede acceso en KernelSU/Magisk.");
                else if (recording) status.setText("● Grabando\n" + last);
                else status.setText("Listo · root disponible\nMotor: AOSP /system/bin/screenrecord");
                setButtons(recording);
            });
        }).start();
    }

    private void setButtons(boolean recording) {
        start.setEnabled(!recording);
        stop.setEnabled(recording);
        start.setAlpha(recording ? 0.45f : 1f);
        stop.setAlpha(recording ? 1f : 0.45f);
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager sbm = (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
            ComponentName component = new ComponentName(this, RecorderTileService.class);
            sbm.requestAddTileService(component, "Grabación de pantalla", Icon.createWithResource(this, R.drawable.ic_screen_record), getMainExecutor(), result -> {});
        } else {
            Toast.makeText(this, "Añade el tile desde Editar ajustes rápidos", Toast.LENGTH_LONG).show();
        }
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundRect(Color.rgb(42,42,49), 18));
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
