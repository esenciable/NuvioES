# Feature: Live Magis nativo en NuvioES (sin server)

## Objetivo
Los canales en vivo de Magis dentro del fork **sin el addon server-side**: el dispositivo habla
directo con el portal (misma arquitectura que Xuper demostró client-side, pero nativa Kotlin).

## Por qué es viable (todo ya existe)
- Protocolo 100% documentado en `kino-light-addon` (TS): portal session, 3DES, device fingerprint,
  catálogos live, `getSlbInfo`, playCode, firma `TweakedMd5.signO3` (port Kotlin verificado en
  `kino-light-main/TweakedMd5.kt`).
- El paquete `com.nuvio.tv.ext.livetv` del fork ya define el contrato de fuentes live (catálogos +
  stream resolution consumidos por la UI de Live TV del F1/F2).
- Sesión: mint anónimo **en el device** (sin pool ni server); cuenta propia opcional en ajustes.

## Fases
- [ ] **M1 — Core Magis en Kotlin**: `MagisCrypto` (3DES puro Kotlin o javax.crypto con clave de
      ajustes), `MagisSession` (mint anónimo device-side, persistencia), `MagisPortal` (host
      failover, endpoints usados). Referencia 1:1: `kino-light-addon/src/magis/*.ts`.
- [ ] **M2 — Catálogos live**: listado de canales (categorías → canales) expuesto con el contrato
      del paquete livetv del fork; nombres/logos desde el portal.
- [ ] **M3 — Playback**: getSlbInfo → playCode → URL playlist CDN directa + headers firmados
      (`signO3`) aplicados por el player (verificar aceptación y ventana); rotación de CDNs y
      re-resolve ante `portal100024`/401.
- [ ] **M4 — Ajustes**: pantalla de configuración (cuenta opcional, hosts override) + estado de
      sesión visible.
- [ ] **M5 — Verificación en device**: playback de 3 canales > 5 min (cruce de la ventana de
      firma), catálogo completo, zonas horarias fuera de alcance (sin EPG).

## Fuera de alcance
- EPG (decisión del dueño: no importa por ahora).
- El addon server-side sigue existiendo para quien lo use; este feature no lo toca.

## Evidencia
- `c0bee1c91` — M1 (core: crypto, device, session, portal, facade) + 13 tests.
- `42b1385dd` — M2 + M3 (sentinel `magis://native-live`, catálogos, playback con headers firmados y
  rotación de CDNs).
- `0f531bb54` — **bugfix de M1**: `reauthenticate()` tomaba el `Mutex` y llamaba `ensureAnonymous()`,
  que toma el mismo `Mutex` (no reentrante) → deadlock en el primer `PortalError`; el live quedaba
  clavado en "Looking for channels..." sin error ni log. Reparado fuera del lock + `MagisSessionTest`
  (RED: timeout de 5s; GREEN: 3/3).
- **M5 verificado en device (2026-10-07)**: pantalla Live TV con **988 canales** (44 ocultos por el
  filtro adulto por keywords), chips por categoría navegables (Deportes, Cine y Series, Venezuela,
  Colombia…) y canal resuelto con la etiqueta de fuente "Magis". Playback real: canal 1 (ECDF)
  reproduciendo **11+ min** cruzando la ventana de firma de 300 s, y zap en pantalla completa a un
  segundo canal con **5.3+ min** sostenidos. Sin `PlaybackException` ni 401/403 del CDN en el log.
  Pendiente honesto: el tercer zap quedó inconcluso porque se colgó el **emulador** (pantalla negra y
  logcat congelado, sin FATAL ni error de player) y hubo que reiniciarlo.
- `40d3041a6` — fix del bridge de plugins (Content-Type case-sensitive) que afectaba al VOD de Magis
  vía plugin; ver la feature doc `premiumtv-live-native.md` para el seam, y el topic
  `bugfix/plugin-content-type-case` en memoria.
