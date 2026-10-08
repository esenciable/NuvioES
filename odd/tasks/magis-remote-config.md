# Feature: Config de Magis remota (sobrevivir rotaciones sin release)

## Objetivo
Que una rotación del portal Magis (hosts, `apkVer`/`spkgVer`, `appId`, versión mínima o la clave
3DES) se arregle con **un push al repo de plugins**, sin sacar un APK nuevo para cada fork instalado.

Hoy `LiveTvMagisModule` construye `MagisRuntimeConfig` leyendo sólo `BuildConfig` (valores horneados
en el build desde `local.properties`), así que el modo de falla conocido de este ecosistema — visto el
2026-10-07 como `portal200001` / `版本已停止使用` — dejaría sin Magis a todos los usuarios del fork.

## Fuente única de verdad
`magis-config.json` en la raíz de `esenciable/esencial-providers` (`00c4b95`), servido por raw:

```
https://raw.githubusercontent.com/esenciable/esencial-providers/main/magis-config.json
```

```json
{
  "schema": 1,
  "updatedAt": "2026-10-07",
  "hosts": ["<host>", "<host>"],
  "appId": "com.android.msandroid",
  "apkVersion": "49902",
  "apkVerHeader": "43404",
  "spkgVer": "2025-08-07 05:40:11_36_16_",
  "threeDesKeyHex": "<48 hex>"
}
```

El mismo archivo alimenta al plugin (`build.cjs` lo lee primero; el `.env` quedó como fallback), así
que plugin y fork **no pueden desincronizarse**.

## Resolución de config (primero válido gana)
1. **Última config buena en disco** (cache local) — permite arrancar sin red.
2. **Fetch remoto** en background, **no bloqueante**.
3. **`BuildConfig`** como fallback final (el comportamiento de hoy si todo lo demás falla).

Reglas:
- El arranque **nunca** espera la red: se arranca con el cache de disco o `BuildConfig` y el remoto se
  adopta cuando llega.
- Un fetch fallido **no** borra la config buena ni arma ningún TTL de "intento hecho" (misma lección
  que en el feed de PremiumTV: sólo un resultado bueno cuenta).
- Refresh remoto como máximo una vez cada N horas por proceso.

## Validación (todo o nada)
El documento se acepta sólo si **todos** los campos son válidos; si uno falla se rechaza el documento
completo y se conserva el anterior (nunca una config a medias):

- `schema == 1`
- `hosts`: lista no vacía, cada host sin esquema ni `/`
- `appId`: no vacío
- `apkVersion` y `apkVerHeader`: sólo dígitos
- `spkgVer`: no vacío
- `threeDesKeyHex`: `^[0-9a-f]{48}$`

## Efecto sin reiniciar la app
La config se lee **por uso**, no capturada en el constructor:
- `MagisPortal.kt` hoy tiene `APK_VER`/`SPKG_VER` como constantes del companion → pasan a leerse de la
  config en cada request (son exactamente el tipo de valor que rota).
- `MagisCrypto` recibe la clave en el constructor → se reconstruye **sólo** cuando la clave cambia.

## Fases
- [x] **R1 — Fetch + validación + cache**: `MagisRemoteConfig` (fetch OkHttp + parseo + validación
      todo-o-nada + cache en disco con `DataStoreMagisConfigStore`, archivo propio `magis_config`,
      device-scoped, que **re-valida el documento al leerlo** para que un cache corrupto caiga al
      fallback en vez de romper) y `MagisConfigProvider` (holder `@Volatile`). Arranque no
      bloqueante: `readOr()` prefiere el disco y si no hay nada usa `BuildConfig`.
- [x] **R2 — Efecto en vivo**: `MagisPortalClient` recibe un `configProvider` + `cryptoProvider` y
      lee la config **por uso**; las constantes `APK_VER`/`SPKG_VER` del companion **se eliminaron**
      (headers, `sysVersion` y device dict salen de la config). `MagisCryptoHolder` reconstruye el
      crypto **sólo** cuando la clave cambia. Refresh remoto: throttle a 6 h por proceso con
      reintento cada 15 min mientras el resultado no sea válido, lanzado de forma asíncrona al
      proveer el gateway (la inyección nunca espera la red). Un fetch fallido o un documento
      inválido **no arma el TTL ni borra nada**: sólo un resultado válido actualiza el sello.
- [ ] **R3 — Verificación**: matriz de validación + orden de fallback + swap del holder ya cubiertos
      por tests (288 tests, 0 fallos). **Falta la prueba en device**: cambiar un valor del JSON
      remoto y comprobar que el fork lo adopta **sin reinstalar** — es el test de aceptación de
      esta feature, y se hace rompiendo a propósito un campo (p. ej. `apkVerHeader`) y revirtiéndolo.

## Nota de implementación (bug de coroutine encontrado)
Un `return` **no local** dentro de `withLock` después de un punto de suspensión **cuelga la
coroutine** (se observó como timeout de 5 s en test). Se usa `return@withLock`. Es la misma clase de
peligro que el deadlock de `MagisSession` (`mutex` no reentrante): las estructuras de
coroutine/`Mutex` castigan las salidas no locales, y el síntoma es un cuelgue silencioso, no un error.


## Fuera de alcance
- Override manual de hosts en Ajustes (eso es M4): acá la config es remota + fallback, no editable.
- Mecanismo de firma del JSON: el repo es público y la clave 3DES ya es pública por decisión del dueño.

## Evidencia
- `00c4b95` (repo del plugin) — `magis-config.json` + `build.cjs`/core/diag leyéndolo: fuente única,
  sin literales duplicados de `apkVer`/`spkgVer`.
- R1 + R2 — 15 tests nuevos (`MagisRemoteConfigTest` 12, `MagisPortalConfigTest` 3); suite
  `ext.livetv.*`: **288 tests, 0 fallos** (baseline 273 met/beaten). Validado contra el JSON remoto
  real: las 8 reglas pasan y el documento completo se acepta.
- R3 pendiente (prueba en device con rotura y reversión deliberadas).
