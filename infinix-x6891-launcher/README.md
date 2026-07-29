# Infinix X6891 Free Fire Launcher

Launcher Android independiente que abre:

- Free Fire: `com.dts.freefireth`
- Free Fire MAX: `com.dts.freefiremax`

## Importante

Este proyecto **no modifica, clona, virtualiza ni inyecta código** en Free Fire.
El perfil Infinix X6891 es únicamente informativo dentro del launcher.

## Compilar desde GitHub Actions

1. Abre la pestaña **Actions** del repositorio.
2. Selecciona **Build Infinix X6891 Launcher**.
3. Pulsa **Run workflow**.
4. Al terminar, descarga el artifact `Infinix-X6891-Launcher-debug`.
5. Dentro estará `app-debug.apk`.

## Personalizar el perfil

Edita:

`app/src/main/java/com/zaid/infinixlauncher/DeviceProfile.java`

## Cambiar paquete lanzado

Edita estas constantes en `MainActivity.java`:

```java
private static final String FREE_FIRE = "com.dts.freefireth";
private static final String FREE_FIRE_MAX = "com.dts.freefiremax";
```
