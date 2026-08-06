#!/system/bin/sh

# Runs before Android's Wi-Fi service reads WifiConfigStore.xml.
# No SSID, passphrase, or PMK is ever written to the log.

MODDIR=${0%/*}
PATCHER="$MODDIR/bin/wifi_psk_compat"
STATE_DIR=/data/adb/duchamp_wifi_wpa2_compat
LOG_FILE="$STATE_DIR/patch.log"
BACKUP_FILE="$STATE_DIR/WifiConfigStore.xml.before-pmk"
STORE_FILE=

umask 077
mkdir -p "$STATE_DIR" || exit 1
chmod 0700 "$STATE_DIR" 2>/dev/null
: > "$LOG_FILE"
chmod 0600 "$LOG_FILE" 2>/dev/null

log_message() {
  printf '%s\n' "$1" >> "$LOG_FILE"
}

for candidate in \
  /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml \
  /data/misc/wifi/WifiConfigStore.xml; do
  if [ -f "$candidate" ]; then
    STORE_FILE=$candidate
    break
  fi
done

if [ -z "$STORE_FILE" ]; then
  log_message "status=store-not-found"
  exit 0
fi

if [ ! -x "$PATCHER" ]; then
  log_message "status=patcher-not-executable"
  exit 1
fi

# Keep the temporary file beside the store so the final rename is atomic.
TEMP_FILE="${STORE_FILE}.duchamp.new.$$"
RESULT_FILE="$STATE_DIR/patch-result.$$"

cleanup() {
  rm -f -- "$TEMP_FILE" "$RESULT_FILE"
}
trap cleanup EXIT

if ! "$PATCHER" "$STORE_FILE" "$TEMP_FILE" > "$RESULT_FILE" 2>> "$LOG_FILE"; then
  log_message "status=patch-failed"
  exit 1
fi

PATCH_RESULT=$(tr -d '\r\n' < "$RESULT_FILE")
case "$PATCH_RESULT" in
  patched=0)
    log_message "status=no-change"
    exit 0
    ;;
  patched=[1-9]*)
    ;;
  *)
    log_message "status=unexpected-result"
    exit 1
    ;;
esac

STORE_OWNER=$(stat -c '%u:%g' "$STORE_FILE") || exit 1
STORE_MODE=$(stat -c '%a' "$STORE_FILE") || exit 1

if [ ! -f "$BACKUP_FILE" ]; then
  if ! cp -p "$STORE_FILE" "$BACKUP_FILE"; then
    log_message "status=backup-failed"
    exit 1
  fi
  chmod 0600 "$BACKUP_FILE" 2>/dev/null
fi

if ! chown "$STORE_OWNER" "$TEMP_FILE" || ! chmod "$STORE_MODE" "$TEMP_FILE"; then
  log_message "status=metadata-failed"
  exit 1
fi
restorecon "$TEMP_FILE" 2>/dev/null

if ! mv -f "$TEMP_FILE" "$STORE_FILE"; then
  log_message "status=replace-failed"
  exit 1
fi

restorecon "$STORE_FILE" 2>/dev/null
sync
log_message "$PATCH_RESULT"
log_message "status=patched"
