# Mod Server Stats Android

Aplicación Android sencilla para consultar directamente la API incluida en el mod NeoForge `modserverstats`.

## Uso

1. En el servidor, activa la API en `config/modserverstats/common.toml`:

   ```toml
   [api]
     enabled = true
     port = 8080
     bindAddress = "0.0.0.0"
     authToken = "el-mismo-token-del-servidor"
   ```

2. Abre la aplicación, escribe la IP o dominio, el puerto y el mismo token en Ajustes.
   El token debe tener al menos 32 caracteres.
3. Pulsa **Actualizar**. Si quieres vigilarlo, activa la actualización automática y elige 5, 10, 30 o 60 segundos.

En la pestaña **CLI** puedes ver la salida del servidor en tiempo real y enviar comandos. Para activarla, añade `consoleEnabled = true` dentro de `[api]` en la configuración del mod. La opción está desactivada por defecto.

La aplicación consulta `http://DIRECCION:PUERTO/api/server/stats`. El botón de puerto permite usar el puerto que te haya asignado el proveedor del servidor.

Además de jugadores, TPS, MSPT, memoria y uptime, muestra el uso de CPU del proceso de Minecraft y la carga total del sistema cuando la JVM proporciona esas métricas.

El selector de gráficos muestra el historial de TPS, MSPT, CPU, memoria, jugadores o latencia. Al conectar, la aplicación solicita las últimas seis horas guardadas por el mod; si no hay historial aún, comienza a registrar la sesión actual.

## Actualizaciones de la app

La aplicación consulta manualmente el manifiesto público de GitHub Releases y descarga desde allí la APK indicada. El enlace está integrado en la app y el mod no necesita actualizarse cuando se publica una nueva versión de la app.

Cada Release publica la APK con su versión en el nombre, por ejemplo `mod-server-stats-app-v1.15.apk`, junto con `latest.json`. Las APK de Release se firman con una clave estable para permitir actualizaciones normales.

No uses `localhost` desde el teléfono salvo que el servidor de Minecraft esté ejecutándose en el propio teléfono. Desde el emulador Android, `10.0.2.2` apunta al ordenador anfitrión.

## Compilación

Con Java 17 y el SDK de Android instalado:

```text
gradlew.bat assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
