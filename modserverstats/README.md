# Mod Server Stats

Mod NeoForge 1.21.1 que expone metricas del servidor y un historial compacto
para la aplicacion Android.

## API integrada

Edita `config/modserverstats/common.toml` despues del primer arranque:

```toml
[api]
enabled = true
port = 8080
bindAddress = "0.0.0.0"
username = "admin"
password = "pon-una-clave-de-12-o-mas-caracteres"
```

La aplicacion usa esa cuenta y guarda las credenciales en el almacenamiento
privado del telefono. Al volver a abrirla no hay que copiar ningun token.
El nombre y la contrasena son de la API del mod, no una cuenta de Minecraft.

La API responde en:

```text
http://SERVER_ADDRESS:PORT/api/server/stats
```

El puerto de Minecraft (`25565`) y el puerto de la API deben ser diferentes.
La API esta desactivada por defecto y no arranca si la contrasena tiene menos
de 12 caracteres.

Las instalaciones anteriores pueden seguir usando `api.authToken` como
autenticacion Bearer durante la migracion, pero las nuevas deben usar usuario
y contrasena.

La autenticacion Basic no cifra el trafico. Para exponer la API en Internet
usa HTTPS mediante un proxy TLS o una VPN privada; no publiques el puerto HTTP
directamente.

## Historial persistente

Por defecto, el mod guarda una fila compacta de SQLite cada 30 segundos en
`config/modserverstats/history/stats.db`. Conserva 30 dias y solo guarda
metricas y cantidad de jugadores.

```toml
[history]
enabled = true
intervalSeconds = 30
retentionDays = 30
maxSamplesPerRequest = 720
```

El historial se consulta con:

```text
http://SERVER_ADDRESS:PORT/api/server/history?minutes=360&limit=720
```
