# Feature: Elegir qué categorías de canales se ven (Opción 1)

## Objetivo
Que el usuario pueda **elegir qué categorías de canales ve** en Vivo, **por proveedor** — incluidas las
**fuentes nativas** (Magis, PremiumTV), que hoy no aparecen en el panel.

## Estado actual (verificado en el código)
- El mecanismo **ya existe**: `hiddenCategoryIds` en `LiveTvStore` (por perfil), el panel muestra una fila
  por categoría con toggle, y la pantalla filtra con `visibleCategoriesFor(categories, hiddenCategoryIds)`.
- **El hueco**: `LiveTvSettingsViewModel` arma su lista con `TvCatalogSelector.select(addons)` → **sólo
  addons**. Las categorías de Magis y PremiumTV (fuentes nativas, no addons) **nunca llegan al panel**,
  aunque el mecanismo ya las soportaría (la clave de ocultado es `LiveTvCategoryId.preferenceKey`, que
  ambas partes derivan del mismo `LiveTvCatalog`).
- El panel renderiza **una lista plana** (`state.categories.filter { it.id.canBeHidden }`), sin agrupar.

## Decisión de diseño: el panel NO carga catálogos nativos
Cargar los catálogos nativos implica caminar el portal (~38 categorías ≈ 15s) o bajar/parsear la M3U de
PremiumTV. El `LiveTvSettingsViewModel` **deliberadamente nunca carga canales** (ver el comentario del
propio archivo sobre Partidos: enumerar desde su estado propio "shipped no rows at all"). Se usa el
**patrón que ya existe para los deportes**: la pantalla de Vivo, que YA carga los catálogos, los
**publica en el store**, y Ajustes los lee.
- `LiveTvStore.rememberSports(Set<String>)` + `knownSports` es el precedente exacto a espejar.
- Consecuencia aceptada (igual que en deportes): antes de abrir Vivo por primera vez, el panel muestra
  los grupos sin filas. Es un estado real, no un bug.

## Fases
- [ ] **C1 — Store**: persistir los catálogos nativos observados (proveedor + `preferenceKey` + label),
      con write **wholesale replace** (no merge), igual que `rememberSports`.
- [ ] **C2 — Publicar**: `LiveTvViewModel`, después de una carga nativa, publica sus catálogos
      (`nativeLoad.catalogs`).
- [ ] **C3 — Leer y agrupar**: `LiveTvSettingsViewModel` une catálogos de addons + nativos observados y
      el panel los renderiza **agrupados por proveedor** (un header por addon/fuente: "Esencial Play",
      "Magis", "PremiumTV"), con las filas de sus categorías debajo. El toggle sigue llamando al mismo
      `onSetCategoryVisible`, con la MISMA clave que usa la pantalla.
- [ ] **C4 — Verificación**: en device, el panel muestra los grupos nativos y al apagar una categoría de
      Magis **desaparece de la grilla** y **sobrevive a un reinicio** (está en el store).

## Fuera de alcance
- Curación de canales y categorías propias (la "Opción 2", una capa de datos aparte).
- Cambiar `visibleCategoriesFor` / la semántica de ocultado: ya funciona y no se toca.

## Evidencia
(pendiente: commits por fase)
