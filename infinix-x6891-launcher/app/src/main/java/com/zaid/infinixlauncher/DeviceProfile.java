package com.zaid.infinixlauncher;

/**
 * Perfil únicamente informativo.
 * No altera Build.*, SystemProperties ni datos vistos por otras aplicaciones.
 */
public final class DeviceProfile {
    private DeviceProfile() {}

    public static final String BRAND = "Infinix";
    public static final String MANUFACTURER = "INFINIX";
    public static final String MODEL = "Infinix X6891";
    public static final String DEVICE = "Infinix-X6891";
    public static final String PRODUCT = "X6891-OP";
    public static final String BOARD = "mt6899";
    public static final String HARDWARE = "mt6899";
    public static final String PLATFORM = "mt6899";

    public static final String ANDROID_RELEASE = "16";
    public static final String SDK = "36";
    public static final String SECURITY_PATCH = "2026-07-01";
    public static final String BUILD_ID = "BP2A.250605.031.A3";
    public static final String DISPLAY_ID = "X6891-16.2.0.150SP16(OP008PF001AZ)";
    public static final String INCREMENTAL = "201500046";
    public static final String FINGERPRINT =
            "Infinix/X6891-OP/Infinix-X6891:16/" +
            "BP2A.250605.031.A3/201500046:user/release-keys";

    public static final String SCREEN = "1208 × 2644";
    public static final String DENSITY = "480 dpi";

    public static String asText() {
        return "Brand: " + BRAND + "\n"
                + "Manufacturer: " + MANUFACTURER + "\n"
                + "Model: " + MODEL + "\n"
                + "Device: " + DEVICE + "\n"
                + "Product: " + PRODUCT + "\n"
                + "Board: " + BOARD + "\n"
                + "Hardware: " + HARDWARE + "\n"
                + "Platform: " + PLATFORM + "\n\n"
                + "Android: " + ANDROID_RELEASE + " (SDK " + SDK + ")\n"
                + "Security patch: " + SECURITY_PATCH + "\n"
                + "Build ID: " + BUILD_ID + "\n"
                + "Display ID: " + DISPLAY_ID + "\n"
                + "Incremental: " + INCREMENTAL + "\n"
                + "Fingerprint:\n" + FINGERPRINT + "\n\n"
                + "Screen: " + SCREEN + "\n"
                + "Density: " + DENSITY;
    }
}
