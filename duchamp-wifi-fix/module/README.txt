POCO X6 Pro Wi-Fi WPA2 Compatibility 2.0

Dispositivo: Xiaomi POCO X6 Pro / duchamp
ROM objetivo: Evolution X oficial, Android 17

QUE HACE
- Mantiene el RRO que desactiva los recursos de auto-upgrade WPA2-SAE.
- Antes del servicio Wi-Fi, corrige solo perfiles WPA2 deshabilitados por
  NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD.
- Convierte localmente la frase WPA2 en su PMK hexadecimal estandar para que
  AOSP no agregue SAE/Cross-AKM a ese perfil.
- Cubre tanto el almacen compartido como el almacen cifrado del usuario 0.
- Vuelve a habilitar el perfil corregido.
- No usa red y no registra SSID, contrasena ni PMK.

INSTALACION
1. Conserva guardada la red que aparece con "contrasena incorrecta".
2. NO la olvides antes del primer reinicio.
3. Instala este ZIP desde KernelSU Next, encima de v1 si estaba instalada.
4. Reinicia Android, desbloquea y espera unos 15 segundos.
5. Prueba la conexion. Si todavia falla y el log contiene
   user-store-ever-patched=1, reinicia una segunda vez.

Si la red todavia no estaba guardada, intenta conectarla una vez y reinicia
despues del fallo para que el modulo pueda corregir el perfil.

COMPROBACION
su
cat /data/adb/duchamp_wifi_wpa2_compat/patch.log

Primer cambio esperado:
patched=1
status=patched

Para el almacen de usuario, las lineas llevan phase=user-early o
phase=user-settled. user-store-ever-patched=1 confirma que fue corregido.

Despues es normal:
status=no-change

El log nunca debe contener credenciales.

RRO:
cmd overlay lookup --user 0 com.android.wifi.resources com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled

El resultado debe ser false. El offload interno puede seguir mostrando true;
la representacion PMK de v2 es la que evita que ese camino agregue SAE.

RESPALDO Y DESINSTALACION
- La primera configuracion original queda, con permisos 0600, en:
  /data/adb/duchamp_wifi_wpa2_compat/WifiConfigStore.xml.before-pmk
- El respaldo del usuario 0 se llama WifiConfigStore-user0.xml.before-pmk.
- Ese respaldo contiene secretos Wi-Fi: no lo compartas.
- Para revertir, elimina el modulo, reinicia y luego olvida y vuelve a agregar
  las redes afectadas.
