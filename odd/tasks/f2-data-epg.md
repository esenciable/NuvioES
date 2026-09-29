# F2 — Datos y EPG

**Estado:** planificado (arranca tras el cierre de F1)
**Rama:** `nuvioes`
**PRD:** `docs/03-PRD-nuvio-es.md` §6 (RF-10 a RF-15, RF-30 a RF-38), §8.6-8.7
**Objetivo de la fase:** que la pantalla muestre **canales reales con guía real**, consumidos del addon
propio, **sin configuración del usuario**.

---

## Lo que ya sabemos del addon (verificado, no supuesto)

El addon propio (`kino-light-addon` / `esencial-play-provider`) ya sirve todo lo que la fase necesita:

| Necesidad | Cómo lo sirve |
| --- | --- |
| Catálogo de canales | Catálogos `type: 'tv'` en el manifiesto (`esencial-play-live-todo`, y el virtual `esencial-play-live-con-guia`) |
| Detección de addon de TV | El fork ya acepta `type` en `{tv, live, channel, iptv, …}` — **no hay nada que cambiar** |
| Reproducción | `/<token>/live/<code>.m3u8` |
| **Guía de programación** | `/<token>/epg.xml` — XMLTV real en el dialecto que el fork lee (`YYYYMMDDHHMMSS +0000`, `<channel id="cyx_…"><display-name>…`) |

### El truco que elimina la configuración manual

El addon define `base = manifestUrl(...).replace(/\/manifest\.json$/, '')` y sirve `${base}/epg.xml`.
Nuestro cliente **ya conoce la URL del manifiesto de cada addon instalado**, así que deriva el EPG con la
misma sustitución de una línea. **No hace falta que el manifiesto declare un campo de EPG** (ni el de
Stremio ni el de upstream lo tienen) **ni que el addon cambie nada.**

### Disciplina obligatoria contra el addon

El fork **resuelve un stream por canal mientras se navega**, para la vista previa. Recorrer un catálogo
llega como **ráfaga**. El addon ya puso un token bucket sobre la resolución
(`ESS_UPSTREAM_PER_MINUTE`, 30/min) sin limitar la reproducción. Nuestra reimplementación debe conservar
esa disciplina: **no pedir streams en cada cambio de foco** y no resolver lo que la vista previa todavía
no necesita.

---

## Tareas

### T1 — Modelo y descubrimiento de canales
- [ ] `ext/livetv/domain/model/` — modelo de canal con clave estable (`stableKey`)
- [ ] `ext/livetv/domain/ChannelCategory.kt` — identidad de categoría **tipada**, nunca texto de UI
      (el fork de referencia usaba el literal `'Favoritos'` como dato y traducir la etiqueta rompía los
      favoritos)
- [ ] `ext/livetv/data/LiveTvSource.kt` — lectura de catálogos `type: 'tv'` vía `AddonRepository` /
      `AddonApi` de upstream
- [ ] Detección **declarativa** de addons de TV (el usuario marca cuáles son), **no** heurística por
      subcadena: la del fork usaba palabras en portugués (`brazuca`, `sexta`, `canal`, `ao vivo`) y
      colaba catálogos de películas

### T2 — Fuentes de EPG: del addon y del usuario
- [ ] Derivar `epgUrl` del addon a partir de su URL de manifiesto (ver arriba) — **sin configuración**
- [ ] Permitir URLs XMLTV propias del usuario (agregar / editar / activar / borrar)
- [ ] Lista de respaldo con **cobertura hispana**: `epgshare01.online` expone `AR1`, `CL1`, `CO1`, `MX1`,
      `PE1`, `UY1`, `ES1`, `US1`, además de `BR1` y `PT1`
- [ ] Divulgar en la UI que la guía viene de fuentes externas, y permitir desactivarlas todas

### T3 — Parser de XMLTV por streaming, con topes
**Es la pieza de mayor riesgo de la fase.** El fork leía el gzip entero en un `String` sin límite y
parseaba con un escáner de strings escrito a mano.
- [ ] Usar `XmlPullParser` (streaming, sin resolución de entidades externas y rechazando DTD)
- [ ] **Tope de bytes durante la descompresión** (corta el stream al superarlo) y **tope de programas**:
      sin esto un gzip bomb tumba una TV de 1-2 GB
- [ ] Tests: XMLTV real con entidades (`&amp;`, `&#233;`) y comillas simples; ventana temporal; canal sin
      programas; **gzip sobredimensionado que debe cortarse**

### T4 — Repositorio de EPG con swap atómico
- [ ] Descargar y parsear **primero**, y recién entonces hacer el swap: el fork borraba las cachés *antes*
      de descargar, así que si todas las fuentes fallaban **perdía la guía que ya funcionaba** y marcaba
      la sincronización como exitosa
- [ ] Estado publicable por `StateFlow` inmutable: el fork leía `HashMap` mutables **sin sincronizar**
      desde otro hilo mientras se limpiaban
- [ ] Caché en disco con TTL y single-flight
- [ ] Emparejamiento **estricto** canal↔EPG por nombre normalizado, con alias de usuario: el fork hacía
      coincidencia por subcadena en cualquier dirección y con "el primero gana", así que `Globo` podía
      matchear `Globonews` y mostrar el programa de otro canal

### T5 — Estados y errores
- [ ] Distinguir **vacío** de **falló** (RF-50): el fork tragaba todos los errores de red y mostraba
      *"no hay canales"* — el usuario no podía distinguir falta de internet de catálogo vacío
- [ ] Estado accionable cuando no hay addons seleccionados (RF-51), con `FocusRequester` explícito

### T6 — Cableado en la pantalla
- [ ] `LiveTvViewModel` como única fuente de verdad
- [ ] La pantalla reemplaza el estado vacío por la lista
- [ ] **Sin literales visibles al usuario en Kotlin** (RF-53): el fork tenía una docena en portugués

---

## Criterio de cierre de F2

1. Con el addon propio instalado, la pantalla **lista canales reales sin configurar nada**.
2. La guía muestra programación real de un canal hispano **con ahora/siguiente**, sin pegar ninguna URL.
3. Los tests del parser, del tope de tamaño y de la coincidencia de canal están en verde.
4. Cortar la red muestra un **error con reintento**, no "no hay canales".
5. El presupuesto de conflicto sigue en **4 archivos de upstream** (esta fase debería costar **0 líneas
   nuevas** de enganche: todo vive en `ext/livetv`).

---

## Decisión pendiente que afecta a F3, no a F2

El presupuesto quedó en **33/40 líneas**. F2 no necesita enganches nuevos, pero **F3 sí**: el handoff al
reproductor principal puede requerir más líneas en `NuvioNavHost.kt`. Antes de F3 hay que decidir si el
techo de 40 se respeta rediseñando, o se sube a conciencia (el PRD exige aprobación explícita para
subirlo).
