# Feature: VOD de Magis nativo en Kotlin (velocidad de nivel addon, sin server)

## Objetivo
Que el VOD de Magis resuelva **en el dispositivo, con estado**, igual que lo hacía el addon: sesión
persistida, SLB cacheado y crypto nativo. Hoy el plugin de Nuvio es un proceso frío sin estado y por
eso tarda ~5.6s.

## Por qué el addon era rápido (medido en su código, no supuesto)
| propiedad del addon | evidencia | ¿el plugin la tiene? |
|---|---|---|
| Sesión persistente en memoria del proceso, reusada entre requests | `src/magis/live.ts:76-82` (`this.sessions`, clave `anon:<installId>`) | **NO**: contexto QuickJS nuevo por llamada y sin storage → mint (2 llamadas) en cada consulta |
| SLB cacheado con margen | `src/magis/resolve.ts:63` (`expiresAt = now + ttl*1000 - 300_000`) | **NO**: `getSlbInfo` siempre |
| Crypto nativo | Node/OpenSSL | **NO**: 3DES en JS puro (286ms medidos en máquina rápida; varias veces más en el ARM del Mi Box) |
| Proceso caliente | server Node | **NO**: contexto por llamada |

Resultado: el addon resolvía con **2 llamadas al portal**; el plugin hace **6** y paga JS crypto.
Lo que ya se recortó del plugin (mint ∥ TMDB, payload 20→10) bajó 1.5s → 1.0s local: no alcanza.

## Fases
- [x] **V2 — Integración como fuente nativa de streams**: `NativeVodSource` (nombre,
      `isConfigured`, resolve propio) en `ext/livetv/domain/`, con `MagisVodSource` como
      implementación sobre el MISMO stack de portal que el live (`MagisPortalStack` compartido en
      `LiveTvMagisModule`: un rate limiter y una sesión para live+VOD) y el TMDB del fork detrás
      de `TmdbTitleLookup`. `StreamRepositoryImpl` emite los grupos de la fuente por el MISMO
      `resultChannel` que los scrapers, en un job propio en paralelo (aislamiento por fuente y
      por job: un fallo nativo se loguea y se salta, nunca rompe la pantalla). La URL nativa es
      CDN directo del portal: sale del repo tal cual, SIN pasar por el camino de scrapers.
      Config inusable → lista de fuentes vacía / `isConfigured=false` → skip, nunca crash.
- [x] **V3 — Orden**: `nativeFirstPluginOrder` en `StreamScreenViewModel` antepone los nombres de
      las fuentes nativas a `pluginOrder`, así "Magis VOD" sigue saliendo primero y no cae en
      `unknownPluginEntries`.
- [ ] **V1 — Cliente VOD nativo** (`ext/livetv/magis/`, reusa el core M1: crypto, device, session,
      portal con failover y rate limit). Port 1:1 de los heuristics YA VERIFICADOS del plugin:
      1. Título/año: **reusar el servicio TMDB del fork** si está disponible (la app ya resolvió el
         título); si no, portar el lookup del plugin.
      2. `v3/searchByName` (`pageSize` 10) → candidatos; selección por tokens del título
         (`magisSelectCandidate`, `magisTokens`, `magisPortalQuery`, `magisSearchItems`) con fallback
         título original → título localizado.
      3. Series: `v4/getItemData` → `contentId` del episodio (`magisEpisodeId`).
      4. `v10/startPlayVOD` → `episodeList[0]` → mejor media (`magisBestMedia`/`magisScoreMedia`:
         h264 > otros, mp4 > otros, mayor altura) → `license`.
      5. `v14/getSlbInfo` → `magisVodCdn`: entrada con `tag == "vod"`, `url_list` con `tag == "free"`
         y `sign_type == "cfl"` (o `sign_type=cfl` en la URL) → `base = main_addr` (con `https://` si
         falta) + `auth`.
      6. URL final: `{base}/vod/{contentId}_media.{ts|mp4}` (la extensión sale de `videoFormat`) con
         headers `Content-Auth`, `Content-License`, `User-Agent: Ranger/4.9.4-17294ac0`,
         `App: {appId}`, `App-Version: {apkVersion}`.
      **Cache de SLB en memoria** con la MISMA lógica del addon (`expiresAt = now + invalidTime*1000 -
      300s`, reuso por tokenOwner): es la mitad del ahorro.
- [ ] **V2 — Integración como fuente nativa de streams**: `StreamRepositoryImpl` ya streamea a un
      `Channel<AddonStreams>`; la fuente nativa emite su propio grupo `AddonStreams("Magis VOD", …)`
      por el MISMO camino (mismo modelo `Stream`), en paralelo con los scrapers y sin bloquearlos.
      Análogo al seam `NativeLiveSource` del live, pero para el pipeline de streams.
- [ ] **V3 — Orden**: la fuente nativa NO está en el registry de scrapers, así que caería en
      `unknownPluginEntries`. Incluir los nombres de las fuentes nativas al PRINCIPIO de `pluginOrder`
      en `StreamScreenViewModel` para que "Magis VOD" siga saliendo primero.
- [ ] **V4 — Verificación (el criterio de aceptación)**: A/B **contra el plugin** con los mismos
      títulos (Coco, Breaking Bad S1E1, y el estreno que el dueño probó), comparando:
      (a) que devuelvan el MISMO stream (comparar el PATH de la URL, no el host: el CDN rota nodos),
      (b) el tiempo real de cada uno. Reportar **medido**, no proyectado. Después, en device.

## Decisiones
- **El scraper de Magis se queda ENCENDIDO en el manifest** (corregido el 2026-10-08 después de
  apagarlo por error): es la **capa portátil** — hace que Magis funcione en cualquier Nuvio, incluido
  stock, que no tiene esta fuente nativa. Apagarlo dejaba sin Magis a todo el que no use el fork.
- **Se convive con un dedup por URL**: los dos caminos resuelven la MISMA URL exacta (verificado byte a
  byte), así que con el scraper encendido el fork mostraría dos filas idénticas. La app descarta un
  stream cuya URL ya vino de un grupo anterior; como la fuente nativa va primero por `pluginOrder`,
  sobrevive la nativa (rápida) y la del scraper desaparece de la vista. **El respaldo queda invisible
  pero real**: si la vía nativa falla, la del scraper es la única y Magis igual funciona.
- El plugin **no se borra**: es el respaldo del fork y la única vía en builds de Nuvio sin este código.
- Los heuristics de selección se portan 1:1 (ya están verificados en el plugin y en el addon); no se
  reinventan. Donde el fork ya tenga un servicio (TMDB), se reusa.

## Fuera de alcance
- Cambiar el camino del live (ya está nativo y verificado).
- Los otros proveedores: siguen siendo scrapers.

## Evidencia
### V1 verificado contra el portal VIVO (A/B real, no proyección)
Harness temporal en la JVM (`VodLiveABTest`, borrado después de correr) construyendo el stack real
desde `BuildConfig` y resolviendo con `MagisVodClient`:

| pasada | tiempo (Mac, V8/JVM) | URL |
|---|---|---|
| Coco #1 (fría: paga el mint) | 1810 ms | `http://buec.kyalbhxgw.com/vod/8D5CCDD31814461180022184FF115D58_media.ts` |
| Coco #2 (caliente: sesión + SLB cacheados) | **767 ms** | idem |
| Breaking Bad S1E1 | 1626 ms | `…/vod/3DF407C5C32C4B95B5235D1A9E026CD6_media.ts` |

**El plugin, mismos títulos, misma máquina**: Coco 958 ms / BB 974 ms → y las URLs son **idénticas
byte a byte** en los dos títulos. O sea: **el port devuelve exactamente el mismo stream** (el criterio
de V4 en su parte de corrección, ya cumplido) y en régimen el cliente nativo ya es más rápido (767 vs
958 ms) **en una máquina donde el 3DES de JS es rápido**; en el Mi Box la ventaja del crypto nativo es
mucho mayor, y el plugin no puede calentarse nunca porque no tiene estado.

### Hallazgo medido: el pacing de 400 ms cuesta ~1 s
`MagisPortal.kt:193` `RATE_LIMIT_MS = 400L` (portado del TS, que espera un turno de cola antes de cada
request). El plugin **no pacea** y el portal lo acepta. Costo estimado del pacing (8-9 llamadas en la
fría, 3 en la caliente): **~900 ms en la fría y ~370-540 ms en la caliente**. Es la próxima
optimización concreta: bajar el mínimo (p.ej. 150 ms) mantiene la cortesía y devuelve ~⅓ del tiempo,
con el A/B como verificación.

### V4a medido: 400 → 150 ms, CERO rechazos en el portal VIVO
Harness temporal (`MagisVodPacingLiveTest`, borrado tras correr) con el stack real desde
`BuildConfig`, resolviendo Coco ×3 y Breaking Bad S1E1 ×2 en una JVM, back-to-back. Dos corridas
completas (10 resolves vivos), `returnCode != 0` en NINGUNA pasada y las URLs byte a byte
idénticas a las del A/B de V1 (mismo criterio de corrección):

| pasada | RATE_LIMIT_MS=400 (baseline) | RATE_LIMIT_MS=150 |
|---|---|---|
| Coco fría (paga mint+TLS) | 5140 ms | 1539 ms (1313 ms en la 2ª corrida) |
| Coco caliente | 767 / 797 ms | 261 / 312 ms (280 / 347 ms 2ª corrida) |
| Breaking Bad S1E1 | 1238 / 1215 ms | 509 / 458 ms (518 / 468 ms 2ª corrida) |

**Se queda 150 ms.** Justificación: el plugin JS envía sin pacing y el portal lo acepta, y la
verificación en vivo a 150 ms no dio ni un solo rechazo en 10 resolves consecutivos — 150 ms es
cortesía sobrante, no requisito del portal — y devuelve la mayor parte de los ~900 ms fríos /
~370-540 ms calientes medidos. Si el portal empieza a rechazar, el revert es una constante.

### Corrección de selección (paridad con el plugin): Dune 1984 jugaba otra película
El dueño reportó (y se reprodujo contra el portal VIVO) que `tt0087182` (Dune 1984) resolvía la
candidata "Tuaregs, los guerreros de las dunas" (2013): `magisSelectCandidate` puntuaba por CONTEO
de tokens, sin año ni `score` del portal, y aceptaba a ciegas la única candidata. Reglas nuevas
(2026-10-09, MISMA decisión escrita dos veces: aquí y en `flat-magis-core.js`):
1. COBERTURA del título pedido (proporción de tokens presentes), con mínimo `MAGIS_MIN_COVERAGE`
   (0.5): una candidata débil se RECHAZA para que el caller pruebe el título original — mejor
   nada que otra película.
2. Año ±1 contra `releaseTime` del portal cuando el lookup trae año (`MagisTitleInfo.year`, llenado
   por `TmdbTitleLookup` en la MISMA llamada de detalles de TMDB). Sin fecha verificable no hay
   gate. `magisReleaseYear` parsea el ISO (`2021-10-22` → 2021).
3. `score` del portal como DESEMPATE entre candidatas de igual cobertura (la cobertura manda).
4. Igual que antes: filtro `programType` series/película y requisito de `contentId`.
5. UMBRAL de cobertura en paridad EXACTA con el plugin: `MAGIS_MIN_COVERAGE = 0.6` (el valor del
   `MAGIS_MIN_COVERAGE` de `flat-magis-core.js`; el borrador aquí quedó en 0.5 y se alineó a 0.6
   el 2026-10-09, opción 4).

**Causa raíz del caso tt1160419 (encontrada por el dueño, no era la selección): era de IDIOMA.**
`TmdbTitleLookup` pedía los detalles de TMDB sin `language` → TMDB contestaba el título en
inglés ("Dune") y el portal (catálogo español) lista "Duna": el único candidato que compartía
palabra era el mockbuster. El plugin pide TMDB con `language=es-MX`. FIX: ambas llamadas
(`getMovieDetails`/`getTvDetails`) llevan `es-MX` (`TmdbTitleLookup.TMDB_LANGUAGE`); la respuesta
en español sigue trayendo `original_title`/`original_name` ("Dune"), así que el par queda
("Duna", "Dune") como el `flatDetail` del plugin y el fallback de título original sigue vivo.
Cero reglas nuevas de selección, nada que tocar en el plugin.

Verificado en vivo (harness temporal JVM borrado tras correr, stack real desde BuildConfig):

| id | lookup (TMDB, es-MX) | seleccionado en vivo | resultado |
| --- | --- | --- | --- |
| tt1160419 (Dune 2021) | ("Duna", "Dune"), año 2021 | **Duna** rel=2021-10-22, score=8.2, dir=Denis Villeneuve | ✔ ANTES (lookup en inglés): mockbuster "Exoplaneta Dune en Peligro" (`A8876A62…`); DESPUÉS: `2753F184…`. El propio pool en vivo traía a "Tuaregs…" y el gate lo apartó |
| tt0087182 (Dune 1984) | ("Dunas", "Dune"), año 1984 | "Dunas" → la ÚNICA candidata era Tuaregs (2013) y se RECHAZÓ → fallback al original → **Dune** rel=1985-03-04, dir=David Lynch | ✔ URL `EBFEC721…` (la película de 1984), igual que en la corrida pre-language |
| tt2380307 (Coco) | ("Coco", "Coco"), año 2017 | **Coco** rel=2017-11-22, score=8.4 | ✔ URL idéntica a la del A/B de V1 (`8D5CCDD3…`) |

Hallazgo del caso tt1160419 (RESUELTO por el fix de idioma, ver arriba): el diagnóstico inicial lo
atribuyó a la regla de selección y propuso un piso de `score` (las DOS selecciones erróneas
observadas tenían `score` 5.0, el mínimo del pool). El dueño identificó la causa real: idioma de
TMDB. El piso de score NO se implementó; quedó descartado como innecesario con el lookup en
es-MX.

### Corrección de TEMPORADAS (paridad con el addon): Ted Lasso S2E1 reproducía S1E1
El dueño reportó que las series se resolvían por temporada roto: S1E1 bien, S2E1 reproducía S1E1.
Causa raíz (descrita VERBATIM en el código del addon, `kino-light-addon/src/magis/provider.ts`
`episodeFor`): el portal entrega los capítulos de UNA temporada por detalle y lista las demás en
`assetData.sameSeasonSeriesList` con su propio `contentId`; si no se pide el de la temporada
pedida, el episodio se elige dentro de la temporada que ya tenía el detalle — la 1.

Port 1:1 de `episodeFor` + helpers (`seasonOfDetail`, `contentIdForSeason`, `episodeFrom`) — MISMA
decisión escrita dos veces (en paralelo también en `esencial-play-providers/lib/flat-magis-core.js`
+ `flat/magis.js`), reglas idénticas:
1. `v4/getItemData(seriesContentId)` → `magisSeasonOfDetail` (lista VACÍA = temporada 1: una serie
   de una sola temporada no aparece en su propia lista) y `magisContentIdForSeason`.
2. Si la temporada pedida vive bajo OTRO contentId, se repite `v4/getItemData` con él y ese detalle
   REEMPLAZA al primero; el episodio sale de ESE detalle.
3. Temporada ausente y detalle identificado como OTRA temporada → SIN resultado (error tipado
   `vod_no_episode`): mejor nada que el capítulo de otra temporada. Si la lista no identifica al
   detalle (`current == null`) y no hay destino, el reference SIGUE con el detalle que ya tenía
   (perder capítulos sería peor).
4. El contentId de la TEMPORADA (no el del show) viaja como `seriesContentId` a `v10/startPlayVOD`:
   la resolución de licencia/playCode va acotada por él.

Verificado en vivo (harness temporal JVM borrado tras correr, stack real desde `BuildConfig`,
mismo método que V1/V4a): **Ted Lasso S1E1 → `…/vod/47C947B369F64AB6A49525B7DE33F46B_media.ts`,
S2E1 → `…/vod/93E32A7390964CF3A9538F2E1D186FD7_media.ts`** — DOS streams DISTINTOS, que es
ejactamente lo que el bug rompía. Suite `com.nuvio.tv.ext.livetv.*`: 363 tests, 0 fallos (baseline
357 + 6 nuevos: la regresión S2E1≠S1E1, lista vacía, temporada ausente, episodio 0, y los helpers
puros).
