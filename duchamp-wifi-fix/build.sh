#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
OUTPUT_DIR="${DUCHAMP_OUTPUT_DIR:-${PROJECT_DIR}/out}"
ZIP_NAME="POCO-X6-Pro-WiFi-WPA2-Compat-v2.0-KSU.zip"

if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT o ANDROID_HOME no esta definido" >&2
  exit 1
fi

AAPT2="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/aapt2"
ZIPALIGN="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/zipalign"
APKSIGNER="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner"
ANDROID_JAR="$SDK_ROOT/platforms/android-36/android.jar"
NDK_BIN="$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
ANDROID_CC="$NDK_BIN/aarch64-linux-android30-clang"
LLVM_READELF="$NDK_BIN/llvm-readelf"
LLVM_STRIP="$NDK_BIN/llvm-strip"
HOST_CC="${HOST_CC:-cc}"

for required in \
  "$AAPT2" \
  "$ZIPALIGN" \
  "$APKSIGNER" \
  "$ANDROID_JAR" \
  "$ANDROID_CC" \
  "$LLVM_READELF" \
  "$LLVM_STRIP"; do
  if [[ ! -x "$required" && "$required" != "$ANDROID_JAR" ]]; then
    echo "Falta herramienta requerida: $required" >&2
    exit 1
  fi
  if [[ "$required" == "$ANDROID_JAR" && ! -f "$required" ]]; then
    echo "Falta herramienta requerida: $required" >&2
    exit 1
  fi
done

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
BUILD_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/duchamp-wifi.XXXXXX")"

cleanup() {
  rm -rf -- "$BUILD_DIR"
}
trap cleanup EXIT

COMPILED_RES="$BUILD_DIR/resources.zip"
UNSIGNED_APK="$BUILD_DIR/DuchampWifiCompatOverlay-unsigned.apk"
ALIGNED_APK="$BUILD_DIR/DuchampWifiCompatOverlay-aligned.apk"
SIGNED_APK="$BUILD_DIR/DuchampWifiCompatOverlay.apk"
KEYSTORE="$BUILD_DIR/duchamp-rro.jks"
HOST_PATCHER="$BUILD_DIR/wifi_psk_compat-host"
ANDROID_PATCHER="$BUILD_DIR/wifi_psk_compat"
MODULE_ROOT="$BUILD_DIR/module"
ZIP_PATH="$OUTPUT_DIR/$ZIP_NAME"

COMMON_CFLAGS=(
  -std=c11
  -O2
  -Wall
  -Wextra
  -Wpedantic
  -Werror
)

"$HOST_CC" "${COMMON_CFLAGS[@]}" \
  "$PROJECT_DIR/native/wifi_psk_compat.c" \
  -o "$HOST_PATCHER"
"$HOST_PATCHER" --self-test
python3 "$PROJECT_DIR/tests/test_wifi_psk_compat.py" "$HOST_PATCHER"

"$ANDROID_CC" "${COMMON_CFLAGS[@]}" \
  -D_FORTIFY_SOURCE=2 \
  -fPIE \
  -pie \
  -ffunction-sections \
  -fdata-sections \
  -Wl,--gc-sections \
  -Wl,--build-id=none \
  "$PROJECT_DIR/native/wifi_psk_compat.c" \
  -o "$ANDROID_PATCHER"
"$LLVM_STRIP" --strip-unneeded "$ANDROID_PATCHER"
"$LLVM_READELF" -h "$ANDROID_PATCHER" | grep -F 'Type:' | grep -F 'DYN'
"$LLVM_READELF" -h "$ANDROID_PATCHER" | grep -F 'Machine:' | grep -F 'AArch64'

"$AAPT2" compile \
  --dir "$PROJECT_DIR/overlay/res" \
  -o "$COMPILED_RES"

"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/overlay/AndroidManifest.xml" \
  --auto-add-overlay \
  -o "$UNSIGNED_APK" \
  "$COMPILED_RES"

"$ZIPALIGN" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"
base64 --decode "$PROJECT_DIR/signing/duchamp-rro.jks.b64" > "$KEYSTORE"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias duchamp-wifi-rro \
  --ks-pass pass:duchamp-rro-2026 \
  --key-pass pass:duchamp-rro-2026 \
  --v1-signing-enabled false \
  --v2-signing-enabled false \
  --v3-signing-enabled true \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"

"$APKSIGNER" verify --min-sdk-version 30 --verbose --print-certs "$SIGNED_APK"

BADGING_DUMP="$("$AAPT2" dump badging "$SIGNED_APK")"
MANIFEST_DUMP="$("$AAPT2" dump xmltree "$SIGNED_APK" --file AndroidManifest.xml)"
MAP_DUMP="$("$AAPT2" dump xmltree "$SIGNED_APK" --file res/xml/overlays.xml)"
RESOURCE_DUMP="$("$AAPT2" dump resources "$SIGNED_APK")"

grep -F "package: name='com.zaid.duchamp.wifi.compat.overlay'" <<<"$BADGING_DUMP"
grep -F "overlay: targetPackage='com.android.wifi.resources' priority='10' isStatic='true'" <<<"$BADGING_DUMP"
grep -F 'targetName' <<<"$MANIFEST_DUMP" | grep -F 'WifiCustomization'
grep -F 'target="bool/config_wifiSaeUpgradeEnabled"' <<<"$MAP_DUMP"
grep -F 'target="bool/config_wifiSaeUpgradeOffloadEnabled"' <<<"$MAP_DUMP"
grep -F '() false' <<<"$RESOURCE_DUMP"

mkdir -p "$MODULE_ROOT/system/product/overlay" "$MODULE_ROOT/bin"
install -m 0644 "$PROJECT_DIR/module/module.prop" "$MODULE_ROOT/module.prop"
install -m 0755 "$PROJECT_DIR/module/customize.sh" "$MODULE_ROOT/customize.sh"
install -m 0755 "$PROJECT_DIR/module/post-fs-data.sh" "$MODULE_ROOT/post-fs-data.sh"
install -m 0755 "$PROJECT_DIR/module/service.sh" "$MODULE_ROOT/service.sh"
install -m 0755 "$ANDROID_PATCHER" "$MODULE_ROOT/bin/wifi_psk_compat"
install -m 0644 "$PROJECT_DIR/module/README.txt" "$MODULE_ROOT/README.txt"
install -m 0644 "$SIGNED_APK" "$MODULE_ROOT/system/product/overlay/DuchampWifiCompatOverlay.apk"

if [[ -e "$ZIP_PATH" ]]; then
  rm -f -- "$ZIP_PATH"
fi

(
  cd "$MODULE_ROOT"
  zip -X -9 -r "$ZIP_PATH" \
    module.prop \
    customize.sh \
    post-fs-data.sh \
    service.sh \
    README.txt \
    bin \
    system
)

unzip -t "$ZIP_PATH"
for required_entry in \
  module.prop \
  customize.sh \
  post-fs-data.sh \
  service.sh \
  README.txt \
  bin/wifi_psk_compat \
  system/product/overlay/DuchampWifiCompatOverlay.apk; do
  unzip -Z1 "$ZIP_PATH" | grep -Fxq "$required_entry"
done

if unzip -Z1 "$ZIP_PATH" | grep -Eiq '(\.jks$|\.keystore$|WifiConfigStore|wifi-auth|framework-res\.apk|ServiceWifiResources\.apk)'; then
  echo "El ZIP contiene un archivo privado o de compilacion no permitido" >&2
  exit 1
fi

sha256sum "$ZIP_PATH" > "$ZIP_PATH.sha256"
cat "$ZIP_PATH.sha256"
echo "Modulo listo: $ZIP_PATH"
