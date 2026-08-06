#!/system/bin/sh

# Handles the credential-encrypted user store once it becomes accessible.
# No SSID, passphrase, PMK, or store contents are written to the log.

MODDIR=${0%/*}
PATCHER="$MODDIR/bin/wifi_psk_compat"
STATE_DIR=/data/adb/duchamp_wifi_wpa2_compat
LOG_FILE="$STATE_DIR/patch.log"
BACKUP_FILE="$STATE_DIR/WifiConfigStore-user0.xml.before-pmk"
MARKER_FILE="$STATE_DIR/user-store-patched"
STORE_FILE=
TEMP_FILE=
RESULT_FILE=

umask 077
mkdir -p "$STATE_DIR" || exit 1
chmod 0700 "$STATE_DIR" 2>/dev/null
touch "$LOG_FILE" || exit 1
chmod 0600 "$LOG_FILE" 2>/dev/null

log_message() {
  printf '%s\n' "$1" >> "$LOG_FILE"
}

cleanup() {
  [ -n "$TEMP_FILE" ] && rm -f -- "$TEMP_FILE"
  [ -n "$RESULT_FILE" ] && rm -f -- "$RESULT_FILE"
}
trap cleanup EXIT

patch_user_store() {
  PHASE=$1
  TEMP_FILE="${STORE_FILE}.duchamp.new.$$"
  RESULT_FILE="$STATE_DIR/patch-result-user.$$"

  if ! "$PATCHER" "$STORE_FILE" "$TEMP_FILE" > "$RESULT_FILE" 2>> "$LOG_FILE"; then
    log_message "phase=$PHASE status=patch-failed"
    cleanup
    TEMP_FILE=
    RESULT_FILE=
    return 1
  fi

  PATCH_RESULT=$(tr -d '\r\n' < "$RESULT_FILE")
  case "$PATCH_RESULT" in
    patched=0)
      log_message "phase=$PHASE status=no-change"
      cleanup
      TEMP_FILE=
      RESULT_FILE=
      return 0
      ;;
    patched=[1-9]*)
      ;;
    *)
      log_message "phase=$PHASE status=unexpected-result"
      cleanup
      TEMP_FILE=
      RESULT_FILE=
      return 1
      ;;
  esac

  if ! STORE_OWNER=$(stat -c '%u:%g' "$STORE_FILE") \
      || ! STORE_MODE=$(stat -c '%a' "$STORE_FILE"); then
    log_message "phase=$PHASE status=metadata-read-failed"
    cleanup
    TEMP_FILE=
    RESULT_FILE=
    return 1
  fi

  if [ ! -f "$BACKUP_FILE" ]; then
    if ! cp -p "$STORE_FILE" "$BACKUP_FILE"; then
      log_message "phase=$PHASE status=backup-failed"
      cleanup
      TEMP_FILE=
      RESULT_FILE=
      return 1
    fi
    chmod 0600 "$BACKUP_FILE" 2>/dev/null
  fi

  if ! chown "$STORE_OWNER" "$TEMP_FILE" || ! chmod "$STORE_MODE" "$TEMP_FILE"; then
    log_message "phase=$PHASE status=metadata-failed"
    cleanup
    TEMP_FILE=
    RESULT_FILE=
    return 1
  fi
  restorecon "$TEMP_FILE" 2>/dev/null

  if ! mv -f "$TEMP_FILE" "$STORE_FILE"; then
    log_message "phase=$PHASE status=replace-failed"
    cleanup
    TEMP_FILE=
    RESULT_FILE=
    return 1
  fi
  TEMP_FILE=
  restorecon "$STORE_FILE" 2>/dev/null
  sync
  : > "$MARKER_FILE"
  chmod 0600 "$MARKER_FILE" 2>/dev/null
  log_message "phase=$PHASE $PATCH_RESULT"
  log_message "phase=$PHASE status=patched"
  cleanup
  RESULT_FILE=
  return 0
}

if [ ! -x "$PATCHER" ]; then
  log_message "phase=user status=patcher-not-executable"
  exit 1
fi

# The owner user's CE directory appears only after the first unlock. Patch it
# as soon as possible, then check once more after boot has settled in case the
# framework wrote an older in-memory copy during the unlock race.
WAIT_COUNT=0
while [ "$WAIT_COUNT" -lt 300 ]; do
  for candidate in \
    /data/misc_ce/0/apexdata/com.android.wifi/WifiConfigStore.xml \
    /data/misc_ce/0/wifi/WifiConfigStore.xml; do
    if [ -r "$candidate" ]; then
      STORE_FILE=$candidate
      break
    fi
  done
  [ -n "$STORE_FILE" ] && break
  WAIT_COUNT=$((WAIT_COUNT + 1))
  sleep 1
done

if [ -z "$STORE_FILE" ]; then
  log_message "phase=user status=store-not-found-after-unlock"
  exit 0
fi

patch_user_store user-early

WAIT_COUNT=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$WAIT_COUNT" -lt 180 ]; do
  WAIT_COUNT=$((WAIT_COUNT + 1))
  sleep 1
done
sleep 10
patch_user_store user-settled

if [ -f "$MARKER_FILE" ]; then
  log_message "user-store-ever-patched=1"
fi
