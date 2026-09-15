# Mod Server Stats Android

Aplicacion Android para consultar la API incluida en el mod NeoForge
`modserverstats`.

## Uso

1. En el servidor, activa la API en `config/modserverstats/common.toml`:

   ```toml
   [api]
   enabled = true
   port = 8080
   bindAddress = "0.0.0.0"
   username = "admin"
   password = "pon-una-clave-de-12-o-mas-caracteres"
   ```

2. Abre la app y escribe la direccion, el puerto, el usuario y la contrasena
   en **Ajustes**.
3. Pulsa **Actualizar**. La app guarda esos datos en el telefono y los usa
   automaticamente en las siguientes aperturas.

La aplicacion muestra jugadores, TPS, MSPT, memoria, uptime y CPU cuando Java
expone esas metricas. El historial se guarda en SQLite dentro del servidor y
se puede consultar por periodo desde la pestana **Historial**. Incluye ventanas
rapidas de 1 hora, 2 horas, 5 horas, 12 horas y 1 dia; la ventana de 1 hora
queda en vivo cuando se usa para seguir las actualizaciones.

El puerto de Minecraft (`25565`) y el de la API deben ser distintos. Desde el
emulador Android, `10.0.2.2` apunta al ordenador anfitrion.

La app usa HTTP Basic. HTTP sin TLS no cifra usuario ni contrasena; para un
servidor accesible desde Internet usa HTTPS mediante una VPN o un proxy TLS.

## Actualizaciones

La app busca actualizaciones solo cuando pulsas el boton correspondiente en
**Ajustes**. El enlace de GitHub esta integrado y la APK se publica con su
version en el nombre.

## Compilacion

Con Java 17 y el SDK de Android instalado:

```text
gradlew.bat assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
