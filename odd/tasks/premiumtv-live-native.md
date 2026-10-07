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
   last-known-good, y **un fetch fallido no arma el TTL**: el reintento vuelve a intentar de
   inmediato en vez de quedarse 30 min sirviendo una lista vacía. Dentro de la ventana del TTL el
   ingreso a la pantalla NO re-descarga los 375 KB (no hay todavía un botón de refresco forzado de
   catálogo; el feed ya expone `load(force)` y el seam puede cablearlo cuando exista ese botón).

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
- [x] **P1 — Abstracción del seam** (`c4cf8de46`): `NativeLiveSource` (identity) + `NativeLiveSources`
      (agregado en la capa data) inyectado como UNA dependencia. El ViewModel ya no nombra ninguna
      fuente; `RoutingLiveTvStreamResolver` rutea sobre N. Magis quedó como una entrada de DI y su
      contrato de config inválida (= fuente ausente) se preserva por fuente. **El ViewModel encogió
      958 → 942 líneas**, que era el requisito de diseño.
- [x] **P2 — Fetch + parser M3U** (`c4cf8de46`): `M3uParser` puro (atributos con y sin comillas,
      títulos con coma, `#EXTVLCOPT` UA/referrer por entrada, líneas vacías, comentarios,
      `#EXTVLCOPT` huérfano, entrada sin URL, grupo sintético si falta `group-title`) y
      `PremiumTvM3uFeed` (OkHttp + UA Chrome, TTL 30 min, re-consulta forzada en refresh manual,
      **last-known-good**). Fixtures con líneas reales de la lista (la entrada TCS con ambos
      `EXTVLCOPT` y la de Samsung vía `jmp2.uk` sin ninguno).
- [x] **P3 — Catálogos + resolución**: `PremiumTvLiveSource` (sentinel `premiumtv://native-live`),
      loader → `LiveTvCatalog`/`LiveTvChannel` por grupo (con unión de `catalogIds`), resolver con
      headers por entrada, follow de redirect para `jmp2.uk`, default Samsung acotado. **No hizo
      falta tocar el ViewModel ni el routing**: el seam de P1 absorbió la segunda fuente completa,
      que era la prueba de que la abstracción estaba bien cortada. El id de canal es el hash corto
      (SHA-256/16 hex) de la URL del stream, así sobrevive a reordenamientos y re-fetch; el resolver
      reconstruye el índice id → entrada desde el documento vigente.
- [ ] **P4 — Verificación en device**: catálogo visible en Live TV junto a Magis, y playback de un
      canal de cada familia (stream directo IP:port, CDN con referrer propio, Samsung vía `jmp2.uk`).

## Fuera de alcance
- EPG (decisión del dueño, igual que en Magis).
- TV+ / iptv-org (excluido explícitamente por el dueño).
- El addon server-side: no se toca.

## Evidencia
- `c4cf8de46` — P1 + P2. Suite `ext.livetv.*`: 253 tests, 0 fallos (baseline 230).
- P3 — PremiumTV como segunda fuente: 20 tests nuevos, suite `ext.livetv.*`: **273 tests, 0 fallos**.
  Precedencia de headers medida y documentada en `PremiumTvStreamResolver`; default Samsung acotado a
  hosts `jmp2.uk`/`samsung` (los 31 `jmp2.uk` son Samsung TV Plus y no declaran referrer).
- Referencia y datos medidos de la fuente: ver `mem_search` topic
  `odd/premiumtv-live-native/tasks`.
