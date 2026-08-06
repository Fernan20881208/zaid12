POCO X6 Pro Wi-Fi WPA2 Compatibility 1.0

Dispositivo objetivo: Xiaomi POCO X6 Pro / duchamp
ROM comprobada: Evolution X, Android 16 (API 36)

Funcion:
- Instala un Runtime Resource Overlay estatico.
- Establece config_wifiSaeUpgradeEnabled en false.
- Establece config_wifiSaeUpgradeOffloadEnabled en false a nivel de recurso.
- No modifica las particiones fisicas ni la contrasena Wi-Fi.

Instalacion:
1. Instala el ZIP interior desde KernelSU Next. No uses recovery.
2. Reinicia Android.
3. Olvida la red Wi-Fi afectada.
4. Vuelve a agregarla y prueba la conexion.

Comprobacion:
su
cmd overlay list --user 0 | grep -i duchamp
cmd overlay lookup --user 0 com.android.wifi.resources com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled
dumpsys wifi | grep -iE 'mIsWpa3SaeUpgradeOffloadEnabled|config_wifiSaeUpgradeEnabled'

El comando lookup debe devolver false. La variable de offload puede seguir
mostrando true por una bandera AOSP de solo lectura; con el perfil WPA2 puro no
deberia introducir SAE en esa red.

Desinstalacion:
- Elimina el modulo desde KernelSU Next y reinicia.
