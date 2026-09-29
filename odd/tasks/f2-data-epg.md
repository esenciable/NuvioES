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

> Las secciones van en el orden en que se hizo el trabajo, no en el orden del plan: el emparejamiento
> estricto (T4) quedó resuelto antes que el parser (T3), porque el índice de la guía no depende del
> parser.

### T1 — Modelo y descubrimiento de canales ✅

**Cerrada.** `ext/livetv/domain/` con **17 tests en verde** (`LiveTvChannel`, `LiveTvCategory`, `TvCatalogSelector`, `LiveTvPaging`).

- [x] Modelo de canal con clave estable: **`(addonBaseUrl, id)`**, nunca la posición en la lista
- [x] Identidad de categoría **tipada** (`LiveTvCategoryId`), sin texto de UI
- [x] Selección **declarativa** de catálogos: `ContentType.TV` o `CHANNEL`, más el marcado del usuario
- [x] Política de paginado con tope y detección de falta de progreso

> ### 🔍 Hallazgo de arquitectura: upstream ya resuelve la descarga de catálogos
>
> `CatalogRepository.getCatalog(...)` devuelve `Flow<NetworkResult<CatalogRow>>` y **ya tiene** el armador de URLs resuelto: preserva la query del addon (el token), maneja `skip` y codifica los argumentos. **No lo reimplementamos.** Beneficios: menos código nuestro, upstream mantiene el paginado y el parseo, y **`NetworkResult` nos da gratis la distinción entre "vacío" y "falló"** que el fork de referencia no tenía (`RF-50`).
>
> `CatalogRow` ya trae `items: List<MetaPreview>` mapeado y `nextSkip` / `hasMore` calculados.

> ### Por qué no se confía en `hasMore`
>
> El paginado se detiene por **cuatro** razones, no una: página vacía, página **sin ítems nuevos**, el addon dice que no hay más, o se llegó al tope de páginas. La segunda existe porque un addon que **ignora `skip`** devuelve la página 1 para siempre: confiar en `hasMore` haría 15 requests en cada carga. El fork de referencia arrastraba `consecutiveDuplicatePages` dentro del modelo de la fila, metiendo una preocupación de paginado en el modelo de UI.

> ### Regresión del fork, fijada con un test
>
> `does not guess from names`: un addon llamado *"Live TV Addon"* con catálogos `type: movie` y `type: series` **no aporta ningún canal**. El fork matcheaba subcadenas contra ids y nombres, con una lista que incluía palabras en portugués (`brazuca`, `sexta`, `canal`, `ao vivo`), así que un catálogo de películas llamado "TV Shows" entraba como fuente de canales.
>
> Y `category identity does not depend on the display name`: la categoría se identifica por `(addonBaseUrl, catalogId)`, así que traducir la etiqueta **no puede** romper los favoritos. En el fork, el literal `"Favoritos"` **era** el valor de la categoría.
>
> El texto que se muestra viene de dos fuentes distintas y no se mezcla: las categorías propias (`All`, `Favorites`) resuelven su etiqueta desde **nuestros** string resources; las del addon usan el nombre que publicó el addon, que es dato, no copy nuestra.

### T2 — Fuentes de EPG: del addon y del usuario (núcleo ✅)

**Núcleo cerrado** con **9 tests** (`EpgSource`, `EpgSourceDiscovery`). Falta cablear el DataStore de
`disabledIds` y la UI de alta/edición de URLs — es T6.

- [x] **Derivar `epgUrl` del addon sin configuración del usuario**
- [x] Lista de respaldo con **cobertura hispana**
- [x] `disabledIds` uniforme: se puede apagar cualquier fuente, incluidas todas (`RF-38`)
- [ ] DataStore de fuentes deshabilitadas y URLs propias (va con T6)
- [ ] Divulgación en la UI de que la guía viene de terceros (va con T6)

> ### La derivación, y el error que casi cometo
>
> El addon publica su guía al lado del manifiesto y **declara la convención él mismo**:
>
> ```js
> const base = manifestUrl(request, token).replace(/\/manifest\.json$/, '');
> // -> `${base}/epg.xml`
> ```
>
> Verifiqué contra `AddonRepositoryImpl.canonicalizeUrl` que **`Addon.baseUrl` ya viene sin
> `/manifest.json`** y **conserva la query**. Entonces la guía está en `path + "/epg.xml" + query`.
>
> El error tentador es concatenar sobre la cadena cruda: con `https://host/token?x=1` eso da
> `https://host/token?x=1/epg.xml`, **donde la query se come el path**. Hay un test para eso.
>
> El premio: un usuario que ya tiene el addon instalado obtiene guía **sin pegar ninguna URL**. Es la
> diferencia entre una función y un ejercicio de configuración.

### T4 — Repositorio de EPG con swap atómico (parcial)

**Hecha la parte de emparejamiento**, con **10 tests** (`EpgChannelNames`, `EpgGuideIndex`). Falta el
repositorio (descarga, caché en disco, single-flight y swap atómico).

- [x] Emparejamiento **estricto** canal↔guía, en **tres niveles**
- [x] Alias explícito del usuario, que gana sobre cualquier conjetura
- [x] **Sin coincidencia por subcadena, nunca**
- [ ] Descarga + caché en disco con TTL + single-flight
- [ ] **Swap atómico**: descargar y parsear primero, y recién entonces reemplazar
- [ ] Publicación por `StateFlow` inmutable

> ### Tres niveles, y por qué el primero es el bueno
>
> 1. **Id exacto.** La guía del addon propio se genera a partir de los mismos canales que se listan, así
>    que sus `<channel id>` coinciden con los nuestros. Cuando pega, el match es exacto y sin juicio.
> 2. **Alias del usuario.** Mapeo explícito, gana siempre.
> 3. **Nombre normalizado único.** Las guías de terceros traen ids ajenos (tipo gatotv), así que el
>    único puente es el nombre.
>
> El fork de referencia caía a **coincidencia por subcadena en cualquier dirección** con "el primero
> gana" sobre un mapa sin orden: `Globo` se ataba a `Globonews` o `Globoplay` y el usuario veía la
> programación de **otro canal** sin ninguna señal de que algo estaba mal. Ese fallback no existe acá,
> y hay un test que lo fija.
>
> **La ambigüedad se rechaza, no se resuelve.** Si dos canales distintos de la guía normalizan al mismo
> nombre, ese nombre queda envenenado y nunca matchea. Elegir uno sería adivinar, y una guía equivocada
> es peor que ninguna.
>
> La normalización es agresiva (mayúsculas, acentos, puntuación y marcadores de calidad) **para que la
> comparación pueda ser exacta**. Ser permisivo ahí es lo que permite que el match sea estricto.

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
