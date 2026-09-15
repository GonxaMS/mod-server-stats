# Mod Server Stats

Mod NeoForge 1.21.1 para consultar métricas del servidor desde una aplicación Android.

## API integrada

El mod expone el estado del servidor directamente desde Minecraft. Edita
`config/modserverstats/common.toml` y configura el puerto asignado:

```toml
[api]
enabled = true
port = 8080
bindAddress = "0.0.0.0"
authToken = "pon-aqui-un-token-aleatorio-largo"
consoleEnabled = false
```

La aplicación Android puede leer el JSON con una petición `GET` a:

```text
http://SERVER_ADDRESS:PORT/api/server/stats
```

El puerto de Minecraft (`25565`) y el puerto de la API deben ser diferentes.
La API está desactivada por defecto.

La API exige `Authorization: Bearer <token>` en todas sus rutas. Si
`api.authToken` está vacío o tiene menos de 32 caracteres, la API no inicia.
Usa un token aleatorio de al menos 32 caracteres y no lo publiques ni lo
incluyas en Git.

## Consola CLI (opcional)

Para habilitar la pestaña CLI de la aplicación, cambia `consoleEnabled` a
`true` dentro de `[api]`. La aplicación recibirá la salida del servidor en
tiempo real mientras la pestaña esté abierta y podrá enviar comandos como
`list`, `say mensaje` u `op jugador`, mostrando también la respuesta generada
en cada ejecución. El mod conserva las últimas 500 líneas solo en memoria.
La consola remota queda protegida por el mismo token. Mantenla desactivada
cuando no la necesites y no expongas el puerto a Internet sin una capa cifrada.

El token autentica al teléfono, pero `http://` no cifra el tráfico. Para una
conexión por Internet usa `https://` mediante un proxy TLS o una VPN privada.

La aplicación consulta incrementalmente la salida en:

```text
http://SERVER_ADDRESS:PORT/api/server/console?after=CURSOR&limit=80
```

La respuesta incluye `processCpuPercent` para la JVM de Minecraft y
`systemCpuPercent` para el equipo. Cualquiera puede ser `null` si Java no
expone esa métrica.

## Historial persistente

Por defecto, el mod guarda una fila compacta de SQLite cada 30 segundos en
`config/modserverstats/history/stats.db`. Conserva 30 días y escribe en un
hilo secundario. El historial guarda solo métricas y cantidad de jugadores;
no guarda nombres ni UUID.

```toml
[history]
enabled = true
intervalSeconds = 30
retentionDays = 30
maxSamplesPerRequest = 720
```

Las muestras históricas están disponibles para la aplicación Android y otros clientes en:

```text
http://SERVER_ADDRESS:PORT/api/server/history?minutes=360&limit=720
```
