# Feature: PremiumTV como segunda fuente live nativa (sin server)

## Objetivo
Sumar los canales en vivo de PremiumTV al Live TV del fork como **fuente nativa**, al lado de la
Magis ya integrada (ver `magis-live-native.md`): el dispositivo lee la lista M3U directo de GitHub y
reproduce el stream con sus headers. Cero server, cero EPG.

## Por qué es la fuente más barata
A diferencia de Magis, **no hay protocolo**: es una lista M3U estática en GitHub raw.
- Lista verificada (2026-10-07): `https://raw.githubusercontent.com/JMigue85/IPTV-SV/refs/heads/main/IPTVSV.m3u`
  — HTTP 200, 375 KB, **1850 canales**, **46 grupos**, 5983 líneas.
- El plugin de Kino lleva la URL ofuscada en base64 (patrón a NO copiar: no aporta nada).
- Sin crypto, sin sesión, sin portal, sin rate limit.

## Lo que hace el plugin de Kino (referencia verbatim)
`ice-dev-x/premiunTV` v1.9.0, capabilities `home/resolve/channels`:
- `atob(...)` → la URL de la M3U; regex `EXTINF` para `tvg-logo` / `group-title` / título.
- Agrupa por categoría con un rename map de emojis (Fútbol→⚽, ESPN→🏅…).
- Caché en memoria 5 min.
- `resolve()`: si la URL viene de `jmp2.uk`, sigue la redirección (UA Chrome) y agrega
  **`Referer: https://www.samsung.com/`** a TODAS las familas.
- Defaults: UA `Chrome/120`.

## Dónde hacemos mejor que el plugin (medido, no supuesto)
La lista **declara headers por canal** y el plugin los ignora:
- 55 entradas traen `#EXTVLCOPT:http-user-agent=…` y 52 traen `#EXTVLCOPT:http-referrer=…`
  (p. ej. `http-referrer=https://teleon.tv/` para los canales TCS de El Salvador).
- Los 31 links a `jmp2.uk` son **Samsung TV Plus** (`stvp-…`, logos en `tvpnlogopeu.samsungcloud.tv`)
  y **no** declaran referrer: ahí sí corresponde `https://www.samsung.com/`. Eso explica el hack
  global del plugin — y es exactamente por lo que hay que acotarlo en vez de copiarlo.

Decisiones de diseño:
1. **Headers por entrada ganan**; el default Samsung se aplica sólo cuando la entrada no declara
   referrer **y** el host es `jmp2.uk` o un host Samsung. Un referrer ajeno puede romper un canal que
   valida el suyo; mandarlo a todos es el defecto del plugin, no su acierto.
2. **Nunca una lista vacía silenciosa**: si el fetch falla, se conserva la última lista buena
   (last-known-good) y la falla se reporta como conteo, sin borrar los canales ya visibles. Éste es
   un defecto explícito del plugin (`M3U muerto = lista vacía silenciosa`) y no se hereda.
3. **TTL más largo que 5 min**: la lista cambia poco y pesa 375 KB. TTL de sesión (~30 min) con
   last-known-good; el refresh manual del Live TV la re-consulta.

## Cambio de diseño en el seam (para no duplicar ramas)
Hoy el `LiveTvViewModel` hardcodea Magis en cuatro lugares (bypass del preview cache,
`resolverAddon`, merge de catálogos, `categoriesFor`). Con una segunda fuente eso se duplicaría.
Se introduce una abstracción mínima `NativeLiveSource` (sentinel `BASE_URL`, `SOURCE_NAME`,
`PLACEHOLDER_ADDON`, `ownsChannel`) y una **lista de fuentes nativas** inyectada por DI:
- `RoutingLiveTvStreamResolver` rutea sobre N fuentes en vez de dos ramas fijas.
- El ViewModel itera las fuentes (carga + merge + categorías) sin nombrar a ninguna.
- Magis pasa a ser una instancia más de esa lista; su comportamiento no cambia.

Regla: el cambio debe **encoger** el código del ViewModel, no agrandarlo.

## Fases
- [ ] **P1 — Abstracción del seam**: `NativeLiveSource` + lista inyectada; Magis adaptada a la lista;
      `RoutingLiveTvStreamResolver` ruteando sobre N fuentes. Sin cambio de comportamiento (suite
      `ext.livetv.*` verde).
- [ ] **P2 — Fetch + parser M3U**: cliente OkHttp (UA Chrome), parser puro de `EXTINF` +
      `#EXTVLCOPT` por entrada (UA/referrer), agrupación por `group-title`, TTL + last-known-good.
      Parser testeado con fixtures reales (grupos, logos, entradas con y sin EXTVLCOPT, líneas
      vacías, `#EXTVLCOPT` huérfano).
- [ ] **P3 — Catálogos + resolución**: `PremiumTvLiveSource` (sentinel `premiumtv://native-live`),
      loader → `LiveTvCatalog`/`LiveTvChannel` por grupo, resolver con headers por entrada, follow de
      redirect para `jmp2.uk`, default Samsung acotado.
- [ ] **P4 — Verificación en device**: catálogo visible en Live TV junto a Magis, y playback de un
      canal de cada familia (stream directo IP:port, CDN con referrer propio, Samsung vía `jmp2.uk`).

## Fuera de alcance
- EPG (decisión del dueño, igual que en Magis).
- TV+ / iptv-org (excluido explícitamente por el dueño).
- El addon server-side: no se toca.

## Evidencia
(pendiente: commits por fase)
