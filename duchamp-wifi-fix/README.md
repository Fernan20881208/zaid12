# POCO X6 Pro Wi-Fi WPA2 Compatibility v2

Módulo para KernelSU Next dirigido al Xiaomi POCO X6 Pro (`duchamp`) con
Evolution X oficial y Android 17.

## Diagnóstico confirmado

La contraseña WPA2 es correcta. En el registro de autenticación, Android crea
un perfil PSK+SAE (`key_mgmt 0x542`) y añade RSNXE al mensaje EAPOL 2/4. El
punto de acceso anuncia WPA2-PSK (`key_mgmt 0x2`) y corta la autenticación con
el motivo 23. Android presenta ese rechazo como «contraseña incorrecta».

El RRO de la v1 sí cambia `config_wifiSaeUpgradeEnabled` a `false`, pero Android
17 vuelve a activar el offload Cross-AKM mediante una bandera de solo lectura.
Por eso el RRO solo no basta en esta compilación.

## Cambio aplicado por la v2

El módulo conserva el RRO de la v1 y, en `post-fs-data`, antes de que arranque
el servicio Wi-Fi:

1. Busca únicamente perfiles WPA2-PSK deshabilitados por
   `NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD`.
2. Deriva en el teléfono el PMK WPA2 estándar con PBKDF2-HMAC-SHA1.
3. Sustituye la frase entre comillas por su representación PMK de 64 dígitos
   hexadecimales y vuelve a habilitar el perfil.

Se cubren los dos lugares usados por Android: el almacén compartido se corrige
en `post-fs-data`; el almacén cifrado del usuario 0 se corrige al desbloquear y
se comprueba otra vez cuando termina el arranque.

AOSP omite la ampliación PSK/SAE cuando el `PreSharedKey` ya es un PMK hexadecimal
de 64 dígitos. El módulo no cambia redes que estén funcionando ni perfiles que
tengan otro motivo de desactivación.

El parcheador es un binario nativo arm64 autocontenido. No usa red, no imprime
SSID, contraseña ni PMK, y GitHub Actions solo compila código y datos de prueba
sintéticos. Ninguna configuración real del teléfono forma parte del repositorio
o del artefacto.

La condición usada está en el método
[`addPskSaeUpgradableTypeFlagsIfSupported`](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-17.0.0_r1/service/java/com/android/server/wifi/SupplicantStaNetworkHalAidlImpl.java)
de AOSP Android 17: una clave de 64 caracteres hexadecimales no recibe los tipos
SAE actualizables. La activación del offload puede verse en
[`ClientModeImpl`](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-17.0.0_r1/service/java/com/android/server/wifi/ClientModeImpl.java).

## Compilación en GitHub Actions

El workflow **Build POCO X6 Pro Wi-Fi WPA2 fix**:

- ejecuta un vector criptográfico conocido;
- prueba la conversión y su idempotencia con XML sintético;
- compila el parcheador Android arm64 PIE con NDK r27c;
- compila, firma y valida el RRO con Android API 36;
- verifica el contenido del módulo y genera su SHA-256.

El artefacto resultante se llama
`POCO-X6-Pro-WiFi-WPA2-Compat-v2.0-KSU` y contiene el ZIP que se instala.
La v2.0 reemplaza por completo a la v1.0; no deben instalarse como módulos separados.

## Instalación

1. Si ya intentaste conectar, conserva la red guardada aunque aparezca como
   «contraseña incorrecta». **No la olvides antes del primer reinicio.**
2. Instala el ZIP interior v2.0 desde KernelSU Next, encima de la v1 si existe.
3. Reinicia Android.
4. Desbloquea el teléfono, espera unos 15 segundos y prueba la red.
5. Si aún muestra «contraseña incorrecta», reinicia una segunda vez. Esto solo
   es necesario cuando el perfil estaba en el almacén cifrado del usuario y el
   servicio Wi-Fi alcanzó a leerlo antes de la primera corrección.

Si la red no estaba guardada, introduce la contraseña una vez, deja que falle y
reinicia. El módulo podrá corregir ese perfil durante el siguiente arranque.

## Comprobación segura

```sh
su
cat /data/adb/duchamp_wifi_wpa2_compat/patch.log
```

En el primer arranque efectivo debe aparecer `patched=1` y `status=patched`,
posiblemente precedidos por `phase=user-early` o `phase=user-settled`.
En arranques posteriores es normal ver `status=no-change`: la conversión es
idempotente. El registro no contiene credenciales.

Si aparece `user-store-ever-patched=1` y la red no conectó en ese mismo arranque,
haz el segundo reinicio indicado arriba.

El RRO también debe seguir activo:

```sh
cmd overlay lookup --user 0 com.android.wifi.resources \
  com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled
```

El resultado esperado es `false`. Que `mIsWpa3SaeUpgradeOffloadEnabled` siga en
`true` es precisamente la razón por la que v2 añade la representación PMK.

## Respaldo y reversión

Antes del primer cambio se guarda una copia con permisos `0600` en:

`/data/adb/duchamp_wifi_wpa2_compat/WifiConfigStore.xml.before-pmk`

Para el almacén cifrado del usuario, el nombre es
`WifiConfigStore-user0.xml.before-pmk` en el mismo directorio.

Ese archivo contiene la configuración Wi-Fi original y debe tratarse como
secreto. No lo compartas ni lo adjuntes a issues.

Para revertir, elimina o deshabilita el módulo en KernelSU Next, reinicia y
después olvida y vuelve a agregar las redes afectadas. No restaures manualmente
un respaldo antiguo sobre una configuración Wi-Fi más nueva.

La clave incluida en el repositorio es una clave de prueba pública usada solo
para mantener estable la identidad del APK del overlay entre compilaciones.
