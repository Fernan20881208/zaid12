#!/system/bin/sh

ui_print "***************************************"
ui_print " POCO X6 Pro Wi-Fi WPA2 Compatibility "
ui_print "***************************************"
ui_print "- Instalando RRO estatico para Wi-Fi"
ui_print "- Instalando correccion local temprana WPA2"
ui_print "- No muestra ni sube credenciales"
ui_print "- No olvides la red antes del primer reinicio"
ui_print "- Reinicia Android para aplicar la correccion"

if [ "${API:-0}" -lt 30 ]; then
  abort "! Android 11 o superior es obligatorio"
fi

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/bin/wifi_psk_compat" 0 0 0755
