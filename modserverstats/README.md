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
```

La aplicación Android puede leer el JSON con una petición `GET` a:

```text
http://SERVER_ADDRESS:PORT/api/server/stats
```

El puerto de Minecraft (`25565`) y el puerto de la API deben ser diferentes.
La API está desactivada por defecto.

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
