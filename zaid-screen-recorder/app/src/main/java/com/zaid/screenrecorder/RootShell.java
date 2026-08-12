package com.zaid.screenrecorder;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class RootShell {
    private RootShell() {}

    static String run(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }
        int rc = process.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("su rc=" + rc + (out.length() > 0 ? ": " + out : ""));
        }
        return out.toString().trim();
    }

    static boolean hasRoot() {
        try {
            return run("id -u").trim().equals("0");
        } catch (Throwable t) {
            return false;
        }
    }
}
