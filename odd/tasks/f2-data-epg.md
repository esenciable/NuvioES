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
- [ ] `ext/livetv/data/LiveTvSource.kt` — lectura de catálogos `type: 'tv'` vía `AddonRepository` /
      `AddonApi` de upstream (firmas ya verificadas: `getInstalledAddons(): Flow<List<Addon>>`,
      `AddonApi.getCatalog(@Url): Response<CatalogResponseDto>`, `enabledAddons()`)
- [ ] Detección **declarativa** de addons de TV (el usuario marca cuáles son), **no** heurística por
      subcadena: la del fork usaba palabras en portugués (`brazuca`, `sexta`, `canal`, `ao vivo`)

### T2 — Fuentes de EPG: del addon y del usuario
- [ ] Derivar `epgUrl` del addon a partir de su URL de manifiesto (ver arriba) — **sin configuración**
- [ ] Permitir URLs XMLTV propias del usuario (agregar / editar / activar / borrar)
- [ ] Lista de respaldo con **cobertura hispana**: `epgshare01.online` expone `AR1`, `CL1`, `CO1`, `MX1`,
      `PE1`, `UY1`, `ES1`, `US1`, además de `BR1` y `PT1`
- [ ] Divulgar en la UI que la guía viene de fuentes externas, y permitir desactivarlas todas

### T3 — Parser de XMLTV por streaming, con topes ✅

**Cerrada.** `ext/livetv/data/epg/` — `XmlTvParser.kt`, `CappedInputStream.kt`, `EpgModels.kt`, con **15 tests en verde**.

**Elección de tecnología**: **SAX** (`javax.xml.parsers.SAXParserFactory`), no `android.util.Xml`. SAX corre en unit tests de JVM puro (los de `android.*` son stubs), decodifica entidades gratis y hace streaming real. Upstream no usa ni SAX ni XmlPull, así que no había idioma de la casa que respetar.

- [x] Parser con `XmlPullParser`/SAX, **sin resolución de entidades externas**
- [x] **Tope de bytes en el lado DESCOMPRIMIDO** (el que ataca una bomba) y tope de programas
- [x] Tests: entidades (`&amp;`, `&#233;`), DOCTYPE real, DTD externo, ventana temporal, canal sin programas, gzip, **bomba de descompresión**, XML malformado

> ### 🔴 El hallazgo que cambió el diseño: los XMLTV reales traen DOCTYPE
>
> Verificado contra la fuente de producción (`epgshare01.online`, archivo `AR1`):
>
> ```xml
> <?xml version="1.0" encoding="UTF-8"?>
> <!DOCTYPE tv SYSTEM "xmltv.dtd">
> ```
>
> O sea que **el consejo habitual de endurecimiento (`disallow-doctype-decl`) habría rechazado TODOS los archivos legítimos**. Está deliberadamente **no** puesto.
>
> El riesgo real de esa cabecera es otro y más acotado: `SYSTEM "xmltv.dtd"` invita al parser a **descargar** `xmltv.dtd`. Eso se bloquea desactivando la carga de DTD/entidades externas, **más un `EntityResolver` que responde vacío a toda referencia externa** como respaldo para plataformas que no soportan esas features (Android no las soporta todas).
>
> **Y se verifica de verdad**: el test escribe un DTD real en disco con `<!ENTITY xxe "EXPANDED-FROM-DISK">`, lo referencia, y exige que esa cadena **nunca** llegue al documento.

> ### Otras decisiones que salieron de los tests
>
> | Situación | Decisión |
> | --- | --- |
> | Tope superado | **Falla** con `EpgLimitExceededException`, no trunca. Una guía truncada es indistinguible de una completa |
> | `DefaultHandler` | Se sobrescriben `fatalError` y `error` para relanzar: por defecto **traga** los errores fatales y un XML malformado devolvería una guía parcial que parece buena |
> | SAX envuelve las excepciones | Se desenvuelve `EpgLimitExceededException` de la cadena de causas, para que "fuente demasiado grande" y "documento malformado" sean distinguibles |
> | Sin offset en la fecha | Un timestamp de 14 dígitos se lee como UTC en vez de descartarse |
> | `<icon>` repetido | Gana el primero (los feeds reales lo repiten) |
> | Programas fuera de la ventana | Se cuentan en `programsSkippedOutOfWindow` en vez de esconderse |
>
> ### Dato del feed real que valida el diseño de emparejamiento
>
> El archivo `AR1` trae ids tipo gatotv (`Canal.13.de.Argentina.(El Trece).ar`), **no** los `cyx_…` del addon propio. Confirma que el emparejamiento canal↔EPG tiene que ser **por nombre normalizado**, no por id — y que el estricto de `RF-36` importa mucho.

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
