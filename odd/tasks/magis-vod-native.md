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
- El plugin de Magis **se queda** como fallback para builds de Nuvio sin este código (stock), no se
  borra.
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
