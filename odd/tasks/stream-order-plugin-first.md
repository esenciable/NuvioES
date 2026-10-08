# Feature: Orden determinista de los streams de plugins (Magis primero)

## Objetivo
Que los streams de los scrapers locales aparezcan en un **orden determinista**, definido por el
`manifest.json` del repo de plugins, en vez del **orden de llegada**. Resultado concreto pedido por
el dueño: **Magis VOD siempre primero** en la lista de streams.

## Por qué hoy queda último (mecanismo medido)
`StreamAutoPlaySelector.orderAddonStreams(streams, installedOrder)`:

```kotlin
val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
return directDebridEntries + orderedAddons + pluginEntries   // ← plugins SIEMPRE al final
```

- Solo los **addons reales** están en `installedOrder` (`addonRepository.getInstalledAddons()`).
- Los **scrapers/plugins** no están ahí → caen en `pluginEntries` y se **anexan en orden de llegada**.
- Magis es el más lento (acuñar sesión + `searchByName` + `startPlayVOD` + `getSlbInfo` ≈ 5.6 s medido
  en device), así que llega último y queda último.

Dato del setup del dueño: `directDebridSourceNames = emptyList()` y `directDebridAvailable = false`,
o sea que no hay Debrid directo: ordenar los plugins alcanza para que Magis quede **primero**.

## Fases
- [ ] **O1 — Orden por registry**: `orderAddonStreams` recibe además `pluginOrder` y ordena
      `pluginEntries` por el índice de ese orden; un nombre desconocido va después, conservando el
      orden de llegada (nada se pierde ni se reordena por accidente).
- [ ] **O2 — Que el orden del manifest gobierne**: `pluginOrder` sale del registry de scrapers
      (`pluginManager.scrapers`, orden de instalación). Hoy `downloadJsScrapers` hace *upsert* y
      **preserva la posición vieja**, así que reordenar el manifest no tendría efecto en instalaciones
      existentes: al refrescar el repo hay que **reordenar la lista según el manifest** (los del
      manifest en su orden, y después los que no figuren).
- [ ] **O3 — Manifest**: mover `magis` al primer lugar del `manifest.json` en
      `esenciable/esencial-providers` (hoy está en el índice 5). El orden pasa a ser **dato**, no
      código: cualquier reordenamiento futuro es un push, sin release del fork.
- [ ] **O4 — Verificación**: test unitario del orden (plugins por registry, desconocidos al final,
      Debrid directo intacto, lista vacía) y en device: Magis VOD aparece primero tras refrescar el
      repo.

## Fuera de alcance
- Cambiar la precedencia del **Debrid directo** (hoy va primero por diseño: es reproducción
  instantánea). Este feature toca el orden **entre plugins**, no el de Debrid.
- Un ajuste de UI para reordenar proveedores: el manifest ya cumple esa función.

## Evidencia
(pendiente: commits por fase)
