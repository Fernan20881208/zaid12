package com.zaid.screenrecorder;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class RecorderTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setLabel("Grabación de pantalla");
            tile.setSubtitle("Comprobando…");
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
        new Thread(this::updateTile).start();
    }

    @Override
    public void onClick() {
        super.onClick();
        getQsTile().setState(Tile.STATE_UNAVAILABLE);
        getQsTile().setSubtitle("Procesando…");
        getQsTile().updateTile();

        new Thread(() -> {
            try {
                if (RootRecorder.isRecording()) {
                    RootRecorder.stop();
                    showToast("Grabación guardada");
                } else {
                    RootRecorder.start(30);
                    showToast("Grabación iniciada");
                }
            } catch (Throwable t) {
                showToast("Error: " + t.getMessage());
            }
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            updateTile();
        }).start();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean recording = RootRecorder.isRecording();
        tile.setLabel("Grabación de pantalla");
        tile.setSubtitle(recording ? "Grabando" : "Lista");
        tile.setState(recording ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private void showToast(String text) {
        getMainExecutor().execute(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }
}
