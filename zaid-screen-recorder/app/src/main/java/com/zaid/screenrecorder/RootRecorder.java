package com.zaid.screenrecorder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class RootRecorder {
    static final String PID_FILE = "/data/local/tmp/zaid_screenrecord.pid";
    static final String LOG_FILE = "/data/local/tmp/zaid_screenrecord.log";
    static final String LAST_FILE = "/data/local/tmp/zaid_screenrecord_last";

    private RootRecorder() {}

    static String start(int bitrateMbps) throws Exception {
        if (bitrateMbps < 4) bitrateMbps = 4;
        if (bitrateMbps > 100) bitrateMbps = 100;

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String output = "/sdcard/Movies/ZaidScreenRecorder/ZaidScreenRecorder-" + stamp + ".mp4";
        String command =
                "mkdir -p /sdcard/Movies/ZaidScreenRecorder; " +
                "if [ -f " + PID_FILE + " ]; then P=$(cat " + PID_FILE + " 2>/dev/null); " +
                "if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null; then echo ALREADY_RECORDING; exit 3; fi; fi; " +
                "rm -f " + PID_FILE + "; " +
                "echo '" + output + "' > " + LAST_FILE + "; " +
                "nohup /system/bin/screenrecord --bit-rate " + bitrateMbps + "M --time-limit 0 '" + output + "' " +
                ">" + LOG_FILE + " 2>&1 </dev/null & " +
                "P=$!; echo $P > " + PID_FILE + "; sleep 1; " +
                "if kill -0 $P 2>/dev/null; then echo 'STARTED:'$P; else cat " + LOG_FILE + "; exit 4; fi";
        RootShell.run(command);
        return output;
    }

    static void stop() throws Exception {
        String command =
                "if [ ! -f " + PID_FILE + " ]; then echo NOT_RUNNING; exit 0; fi; " +
                "P=$(cat " + PID_FILE + " 2>/dev/null); " +
                "if [ -z \"$P\" ] || ! kill -0 \"$P\" 2>/dev/null; then rm -f " + PID_FILE + "; echo NOT_RUNNING; exit 0; fi; " +
                "kill -2 \"$P\" 2>/dev/null || true; sleep 2; " +
                "if kill -0 \"$P\" 2>/dev/null; then kill -15 \"$P\" 2>/dev/null || true; fi; " +
                "rm -f " + PID_FILE + "; echo STOPPED";
        RootShell.run(command);
    }

    static boolean isRecording() {
        try {
            String out = RootShell.run("P=$(cat " + PID_FILE + " 2>/dev/null); if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null; then echo 1; else echo 0; fi");
            return out.trim().endsWith("1");
        } catch (Throwable t) {
            return false;
        }
    }

    static String lastOutput() {
        try {
            return RootShell.run("cat " + LAST_FILE + " 2>/dev/null || true");
        } catch (Throwable t) {
            return "";
        }
    }
}
