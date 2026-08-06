# POCO X6 Pro Wi-Fi WPA2 Compatibility

Módulo para KernelSU Next dirigido al Xiaomi POCO X6 Pro (`duchamp`) con
Evolution X y Android 16 (API 36).

## Motivo

El diagnóstico de la conexión mostró que la contraseña WPA2 era correcta y que
el punto de acceso aceptaba la asociación, pero rechazaba la autenticación justo
después del segundo mensaje EAPOL. La configuración de Evolution X añadía SAE y
Cross-AKM al perfil WPA2, provocando una incompatibilidad con ese router.

## Cambio aplicado

El módulo instala un RRO estático para `com.android.wifi.resources` y establece:

- `config_wifiSaeUpgradeEnabled=false`
- `config_wifiSaeUpgradeOffloadEnabled=false`

No contiene contraseñas, direcciones MAC, registros, configuraciones Wi-Fi ni
los APK de sistema usados para el diagnóstico.

## Compilación en GitHub Actions

El workflow **Build POCO X6 Pro Wi-Fi WPA2 fix** compila el overlay con Android
API 36, lo alinea, lo firma, valida sus recursos y genera el módulo ZIP.

1. Abre la pestaña **Actions** del repositorio.
2. Entra al workflow y abre la ejecución más reciente que esté en verde.
3. Descarga el artefacto `POCO-X6-Pro-WiFi-WPA2-Compat-v1.0-KSU`.
4. Extrae el contenedor descargado y conserva el ZIP interior sin extraerlo.
5. Instala ese ZIP interior desde KernelSU Next y reinicia.
6. Olvida la red afectada y vuelve a agregarla.

## Comprobación

```sh
su
cmd overlay lookup --user 0 com.android.wifi.resources \
  com.android.wifi.resources:bool/config_wifiSaeUpgradeEnabled
```

Debe devolver `false`. `mIsWpa3SaeUpgradeOffloadEnabled` puede seguir mostrando
`true` debido a una bandera AOSP de solo lectura; el valor importante para este
overlay es el resultado del comando anterior.

## Revertir

Elimina el módulo desde KernelSU Next y reinicia.

La clave incluida es una clave de prueba pública usada únicamente para mantener
estable la identidad del APK entre compilaciones. No protege datos ni concede
privilegios por sí sola.
