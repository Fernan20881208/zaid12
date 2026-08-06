#!/system/bin/sh

ui_print "***************************************"
ui_print " POCO X6 Pro Wi-Fi WPA2 Compatibility "
ui_print "***************************************"
ui_print "- Instalando RRO estatico para Wi-Fi"
ui_print "- Objetivo: com.android.wifi.resources"
ui_print "- Reinicia y vuelve a guardar la red Wi-Fi"

if [ "${API:-0}" -lt 30 ]; then
  abort "! Android 11 o superior es obligatorio"
fi

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
