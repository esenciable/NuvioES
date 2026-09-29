# F6 — Zapping, restauración de foco, ajustes en su sitio y slider de categorías

**Estado:** planificado. Escrito para arrancar en una **sesión nueva** (la anterior llegó a su límite
de contexto).
**Rama:** `nuvioes`. **Punto de partida:** commit `c74c72de4`.
**PRD:** `docs/03-PRD-nuvio-es.md` (repo hermano `../nuvio/`).

---

## Punto de partida, para no re-descubrir nada

| | |
| --- | --- |
| Tests de la feature | **117, 0 fallos** |
| Presupuesto de conflicto | **4/4 archivos, 33/40 líneas** |
| Suite de upstream | 1690 tests, **18 fallos = los 18 conocidos** (`nuvioes/known-test-failures.txt`) |
| Verificado en Android TV | canales (1170), guía (13152 programas), reproducción, panel de vista previa, foco estable, ajustes, grilla EPG |

**Antes de tocar nada:** `./nuvioes/check-conflict-budget.sh` y leer la sección *"Estado real"* de
`NUVIO-ES.md`.

---

## Tarea 1 — Zapping con ARRIBA/ABAHO (el pedido central)

### La investigación ya está hecha: el fork NO usa el reproductor de upstream

```
TvChannelsScreen.kt:331    if (state.isFullscreen) {
TvChannelsScreen.kt:344        TvChannelFullscreenHud(...)
```

Renderiza el **mismo `ExoPlayer` del panel de vista previa** (`TvChannelPreviewPlayerPool`) a pantalla
completa, dentro de **su propia** pantalla. Y por eso decide las teclas:

```
TvChannelFullscreenHud.kt:156    Key.DirectionUp,   Key.PageUp   -> onPreviousChannel()
TvChannelFullscreenHud.kt:162    Key.DirectionDown, Key.PageDown -> onNextChannel()
```

En el reproductor de upstream, ARRIBA/ABAJO pertenecen a **sus** controles. **Una superficie propia es
toda la diferencia.**

### Cómo elige el canal siguiente — copiar esto

`TvChannelsRepository.selectNextChannel` (línea 810):

```kotlin
val list = current.filteredChannels
val currentIndex = list.indexOfFirst { it.stableKey() == current.previewChannel?.stableKey() }
val nextIndex = if (currentIndex in list.indices) (currentIndex + 1) % list.size else 0
```

- **Sobre la lista ya filtrada** → zappear **no puede esquivar el filtro parental** ni salirse de la
  categoría. Es lo contrario del agujero del traspaso al reproductor principal.
- **Por `stableKey`, no por posición.**
- **Envuelve** (`% list.size`): del último canal se vuelve al primero.

### Decisión: UN SOLO reproductor, el nuestro (decidido por el dueño)

**Nuestro propio fullscreen, reutilizando el reproductor del panel. Cuesta CERO enganches**, porque la
pantalla ya es dueña de la decisión de pantalla completa (igual que el panel de ajustes).

Los canales de TV no necesitan subtítulos ni pistas, así que mantener dos reproductores es complejidad sin
premio. **Y la contra que yo había planteado no se sostiene**: miré el builder de upstream y lo que aporta
de más es menos de lo que parecía.

```kotlin
.setLoadControl(loadControl)                       // es UN helper invocable desde nuestro código
.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)   // apagado A PROPÓSITO
```

- El **`LoadControl`** es `NuvioExoPlayerPerformanceHelper.buildLoadControl(context)` — una llamada, y se
  lleva el tuning por franjas de RAM, que sí importa en un TV box flojo.
- **El frame-rate switching está apagado** en upstream, así que no hay argumento de judder que perder.
- Un `BandwidthMeter` es una línea.

### Beneficio extra: eliminar el traspaso elimina la fuga del filtro parental

El P0 que encontré (`SEC-3` / `A1`) era exactamente el handoff a `Screen.Player`: **no consultaba el
filtro**, así que un canal adulto ya en vista previa podía abrirse con el control parental activo. Con un
solo reproductor **ese camino deja de existir**: no hay manera de llegar a un canal sin pasar por la lista
filtrada.

### Lo que se pierde, dicho con honestidad

El reproductor de upstream además aporta `RenderersFactory` (preferencias de decodificador, conversión
DV7) y toda su interfaz de pistas y subtítulos. Para canales lineales es un intercambio aceptable. **Si
más adelante aparece un canal con subtítulos o audio dual que importe**, ése es el momento de reevaluar el
traspaso — no antes.

### ⚠️ La trampa a evitar, y es nuestro propio hallazgo P0 (A3)

El fork **consume las cuatro direcciones** en el `Box` raíz con foco → **cuando la reproducción falla,
"Reintentar", "Siguiente" y "Atrás" quedan inalcanzables con el control remoto**.

Nuestra versión: **en estado de error, dejar de consumir las direcciones** y pedir foco al botón de
reintentar. Y **un solo `ExoPlayer` reutilizado** entre canales, no uno nuevo por canal.

### Tareas
- [x] `LiveTvViewModel.nextChannel()` / `previousChannel()` sobre la lista filtrada, por `stableKey`, con wrap (puro en `LiveTvZapping.kt`, commit `5e3a36c3d`)
- [x] Tests: wrap desde el último, canal actual ausente de la lista, lista vacía, y que respeta el filtro (10 tests en `LiveTvZappingTest`; 127 en total, 0 fallos)
- [x] Estado `isFullscreen` + superficie propia reutilizando `createPreviewPlayer` (`LiveTvFullscreenSurface.kt`)
- [x] **Un solo reproductor**: agregarle `NuvioExoPlayerPerformanceHelper.buildLoadControl(context)` y un
      `BandwidthMeter`. **Quitar el traspaso a `Screen.Player`** para canales de TV (commit `5e3a36c3d`)
- [x] Verificar que **ya no existe** el camino que abría un canal sin consultar el filtro adulto (grep de `Screen.Player` en el paquete: solo el comentario de doc)
- [x] Manejo de teclas: ARRIBA/ABAJO zappean; **en error NO se consumen** (foco va a Reintentar)
- [x] HUD mínimo: nombre del canal + programa al aire (y el hint de teclas, en palabras)
- [x] Verificar en dispositivo: wrap, error con Reintentar pulsable, y que un solo player sobrevive (commit `5e3a36c3d`: DOWN zappeó al canal 2 con HUD nuevo; UP desde el primero wrappeó al último de 1172 — sin streams, el addon devuelve 0 — y el overlay "Try again" quedó enfocado y pulsable; BACK volvió a la lista con foco en el canal zappeado; 0 crashes en todo el recorrido)

---

## Tarea 2 — Restauración de foco al volver al panel

### Diagnóstico (leído del código, **falta confirmarlo en dispositivo**)

`LiveTvScreen.kt`, en `ChannelList`:

```kotlin
LaunchedEffect(Unit) {
    focusedKey = firstKey
    runCatching { listFocusRequester.requestFocus() }   // ← SIEMPRE la fila 1
}
```

- Al volver del reproductor o al re-componerse tras un refresh, el efecto vuelve a correr y pide foco
  **a la primera fila**, no al canal donde estaba el usuario.
- `listFocusRequester` es **un solo requester atado a la primera fila**, así que no hay manera de pedir
  foco en otra.

### El arreglo, y la trampa en la que el fork cayó

Recordar el canal enfocado **fuera** del composable y pedirle foco **a ese**, con fallback al primero si
ya no existe (búsqueda, cambio de categoría o filtro pudieron sacarlo).

**La trampa**: los `FocusRequester` deben estar indexados por **`stableKey`**, nunca por posición. El fork
los indexaba por índice de lista mientras los ítems usaban `stableKey`, así que tras cualquier filtro el
ítem que sobrevivía en la posición *i* reusaba el requester de **otro canal ya destruido**. Es el
hallazgo `UI-11` de la auditoría.

Alternativa más simple y probablemente suficiente: no pedir foco al re-componer si ya hay algo enfocado
(`focusedKey != null`), y confiar en la restauración de foco que Compose ya hace por `key`.

### Tareas
- [x] Reproducir el bug en dispositivo y **confirmar** el diagnóstico (no heredarlo como hecho)
- [x] Arreglar con requester por `stableKey` **o** no re-pedir foco si ya hay foco
- [x] Verificar: entrar al canal 8, ver, volver → el foco queda en el 8 (verificado: A3S, commit `f238ea172`)
- [ ] Verificar: tras un refresh (que desmonta la lista) el foco queda donde estaba

---

## Tarea 3 — Los ajustes, donde están los de la app original

Hoy los ajustes de la feature son un **estado de nuestra pantalla** (botón "Ajustes" en el encabezado),
elegido así **para no gastar líneas de enganche**.

El dueño quiere que estén donde están los de la app original: una **categoría dentro de `SettingsScreen`**,
que es lo que hizo el fork.

### ✅ APROBADO: esto ya es una tarea, no una decisión pendiente

El dueño aprobó el 5.º enganche (~17 líneas / 5 archivos). Los ajustes van a **donde están los de la app
original**: una categoría dentro de `SettingsScreen`.
El guardarraíl y `NUVIO-ES.md` ya están actualizados: límite nuevo **5 archivos / 60 líneas**
(estábamos en 4/40 con 33 usadas).

Lo que costó, medido contra el fork:

| Punto de enganche | Líneas |
| --- | --- |
| Import de `Icons.Rounded.Tv` | 1 |
| Miembro `SettingsCategory.TV_CHANNELS` | 1 |
| Bloque `SettingsSectionSpec(...)` | ~7 |
| Entrada en el mapa de `FocusRequester` | 1 |
| Rama del `when` en `SettingsDetailPane` | ~7 |
| **Total** | **~17** |

O sea: el presupuesto pasaría de **33/40** a **~50** líneas y de **4** a **5 archivos**. Las dos
opciones honestas:

1. **Subir el presupuesto** de 40 a ~60 líneas y 5 archivos, con la justificación escrita en `NUVIO-ES.md`
   y en el guardarraíl. Es una decisión del dueño, no mía: el PRD exige aprobación explícita.
2. **Mantener el panel interno** y mejorar su descubribilidad (hoy es un botón en el encabezado).

Recomendación: **la opción 1**, porque los ajustes de una feature deben vivir donde el usuario ya busca
ajustes, y 17 líneas es un precio bajo comparado con el resto del proyecto. Pero **es una excepción al
presupuesto**, así que se aprueba o no se hace.

### Tareas
- [x] Decisión del dueño sobre el presupuesto: **aprobado**
- [x] `SettingsCategory.TV_CHANNELS` + spec + focusRequester + rama del `when`, con el panel reutilizado
      tal cual (no reescribirlo). **Medido: 18 líneas, 5/5 archivos, 51/60**. Commit `67152a924`
- [x] Decisión: **el botón "Ajustes" del encabezado se queda como atajo.** Los dos caminos renderizan el
      mismo panel sobre el mismo store, así que no pueden divergir, y el usuario está en TV en vivo
      cuando quiere ajustes de TV en vivo
- [ ] Verificar en dispositivo: Ajustes → TV en vivo → el panel, con foco correcto y Atrás que vuelve
      (**bloqueada**: el addon del dueño devuelve 502/timeout en todos los catálogos, así que no hay
      categorías que listar; reverificar cuando vuelva)
- [x] Actualizar la tabla de enganches y el conteo en `NUVIO-ES.md` cuando esté hecho (medido: **18 líneas,
      51/60, 5 archivos**; el margen que queda es de 9 líneas)

---

## Tarea 4 — Slider de categorías, configurable en ajustes

El dueño quiere **un slider horizontal con las categorías de canales arriba de la lista**, como el fork,
y **elegir en ajustes qué categorías aparecen en ese slider**.

### Lo que ya existe (no hace falta inventarlo)

- `LiveTvRows.categoriesFor(catalogs)` → arma la lista: **`All`, `Favorites`**, después un ítem por
  catálogo del addon, en orden. **Ya está escrito y testeado.**
- `LiveTvCategoryId` es **tipado** (`All` | `Favorites` | `Addon(addonBaseUrl, catalogId)`): la identidad
  **no es texto de UI**, así que traducir una etiqueta no puede romper la selección. (El fork usaba el
  literal `"Favoritos"` como valor y traducir la etiqueta rompía los favoritos.)
- `List<LiveTvChannel>.inCategory(category, isFavorite)` → el filtrado, ya testeado.
- `state.categories` y `state.selectedCategory` **ya están en el estado y hoy NO tienen UI**: es estado
  muerto. Esta tarea le pone la UI que le falta.

### Diseño recomendado del slider

- Fila horizontal **arriba de la lista**, con los chips: `Todos`, `Favoritos`, y los catálogos del addon.
- El chip activo se marca visualmente; al elegirlo se llama a `setCategory(id)`.
- **Foco**: el chip activo recibe foco al entrar al slider; ARRIBA desde la primera fila de canales llega
  al slider, ABAJO vuelve a la lista. **No** debe robarle el foco de entrada a la lista (el usuario entra
  a ver canales, no a elegir categoría).
- Los ítems del slider salen de `state.categories`, filtrados por la selección de ajustes (tarea 4b).

### 4b — Elegir en ajustes qué categorías van al slider

- Nueva preferencia por perfil en `LiveTvStore`: `hiddenCategoryIds: Set<String>` (mismo criterio que las
  fuentes de EPG: se guardan **las ocultas**, así el conjunto vacío significa "todas", sin ambigüedad).
- En el panel de ajustes, un grupo **"Categorías en el slider"** con un `SettingsToggleRow` por categoría.
- **`All` y `Favorites` no se pueden ocultar**: sin `Todos` la lista no tiene estado por defecto, y
  `Favoritos` es una función, no una categoría del addon.

### ⚠️ Trampas conocidas

- **No meter un `LazyRow` dentro del `LazyColumn`** de canales: es el patrón que hizo stutterear al fork.
  Un `Row` con `horizontalScroll` compartido, como en la grilla.
- Si el canal **actual deja de estar en la categoría** elegida, el panel de vista previa puede quedar
  mostrando un canal que ya no está en la lista: **limpiar el preview** en ese caso (el fork no lo hacía y
  dejaba un canal que ya no se podía ver en la lista).
- El contador del encabezado debe reflejar la **categoría**, no el total.

### Tareas
- [x] Slider arriba de la lista, con chips y estado activo visible (`LiveTvCategorySlider.kt`, commit en HEAD)
- [x] `setCategory` conectado (el método **ya existe** en el ViewModel)
- [x] Código: focusRestorer al chip activo y `focusProperties { up = ... }` en la primera fila. Falta la verificación en dispositivo
- [x] `hiddenCategoryIds` en `LiveTvStore` + grupo en el panel de ajustes (sin permitir ocultar `Todos`/`Favoritos`)
- [x] Tests puros: `visibleCategoriesFor` (5 tests; 132 en total, 0 fallos) + `All`/`Favorites` no se ocultan
- [x] Limpiar el preview cuando el canal sale de la categoría (`clearPreviewIfChannelLeftCategory`)
- [ ] Verificar en dispositivo: cambiar categoría, el contador, el foco, y que el preview no quede colgado

---

## Trampas operativas (aprendidas a golpes, no las repitas)

1. **`uiautomator dump` no es confiable.** Tras unas docenas de llamadas falla con
   `UiAutomationService already registered` y después devuelve **árboles obsoletos** — una pantalla sana
   pareció colgada en *"Looking for channels…"*. Para manejar la TV: `adb shell input keyevent` +
   `adb exec-out screencap -p`.
2. **Verificá `mCurrentFocus` antes de mandar teclas**: `adb shell dumpsys window | grep mCurrentFocus`.
   `monkey` puede dejar la app **visible pero sin foco**, y entonces todo keyevent va a otra app y
   terminás diagnosticando un foco inexistente. `am start -n <actividad>` sí le da foco
   (`cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER <pkg>`).
3. **`adb logcat -c` antes de cada prueba** y después contar `FATAL EXCEPTION`. Una app muerta y una sin
   foco se parecen mucho en una captura. Un `grep -c` de 0 es la prueba más barata de que sobrevivió.
4. **Nunca interpolar cadenas con `$` por argumentos de shell entre comillas dobles**: `%1$d` se volvió
   `%1` y eso **mataba la app** al renderizar (`UnknownFormatConversionException`). Con `perl`, además,
   `-CSD` sin `-Mutf8` no matchea acentos y falla en silencio.
5. **`nuvioes/focus-probe.py`** dice qué fila tiene el foco desde un `screencap` **crudo** (sin PIL). Para
   "¿el foco se movió solo?", comparar capturas espaciadas **sin mandar teclas**.
6. **Al rebasar**: comparar los fallos de test contra `nuvioes/known-test-failures.txt`, **no contra cero**.
   El pin de upstream **viene rojo con 18 fallos**.

---

## Orden recomendado

1. **Tarea 2 (foco) primero.** Es un bug que el usuario ya notó, es chico, y arreglarlo antes de tocar el
   foco del slider evita apilar dos cambios de foco.
2. **Tarea 1 (zapping).** El valor más alto y cero presupuesto.
3. **Tarea 4 (slider)**, que también depende del foco.
4. **Tarea 3 (ajustes en su sitio)** al final: **ya está aprobada**, y el guardarraíl la admite.
