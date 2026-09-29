# F1 — Esqueleto del feature e integración

**Estado:** cerrado
**Rama:** `nuvioes` (commit directo: la fase es el bootstrap del seam)
**PRD:** `docs/03-PRD-nuvio-es.md` §10 (repo hermano `../nuvio/`)
**Objetivo de la fase:** que el seam de integración sea **real y compilado** antes de escribir una sola línea de feature encima.

---

## Por qué esta fase existe

El PRD ordena F1 antes que F2 por una razón concreta: si la feature se escribe primero, cada archivo nuevo
se apoya en una integración **no probada**, y el día del primer rebase hay que resolver las dos cosas a la
vez. F1 existe para probar que **4 archivos de upstream y 23 líneas alcanzan**, y que el proyecto compila y
corre en un dispositivo con eso.

---

## Tareas

### T1 — Código propio, aislado

- [x] `app/src/main/java/com/nuvio/tv/ext/livetv/LiveTvRoute.kt` — punto de entrada único; recibe el
      `NavController` para que el enganche de upstream sea **una línea** y la adaptación futura quede de
      nuestro lado
- [x] `app/src/main/java/com/nuvio/tv/ext/livetv/ui/LiveTvScreen.kt` — pantalla con estado vacío real
- [x] `app/src/main/res/values*/strings_livetv.xml` en **4 locales**: `values/`, `values-es/`,
      `values-b+es+419/`, `values-pt-rBR/`
- [x] **Ningún archivo de upstream recibió una cadena de texto.** Android fusiona todos los `.xml` de
      `res/values`, así que la feature tiene su propio archivo y las traducciones nunca generan conflicto

### T2 — Los 4 enganches

- [x] `Screen.kt` — 1 línea: `data object LiveTv : Screen("livetv")`
- [x] `NuvioNavHost.kt` — 1 import + 1 `composable` que delega en `LiveTvRoute(navController)`
- [x] `MainActivity.kt` — import del ícono, alta en `rootRoutes`, string, clave del `remember` y el
      `DrawerItem`
- [x] `app/build.gradle.kts` — el updater in-app apunta a **nuestro** repo, no a NuvioMedia

### T3 — Verificación del presupuesto

- [x] `./nuvioes/check-conflict-budget.sh` → **`4/4 archivos, 23/40 líneas`**, presupuesto OK
- [x] Arreglado el guardarraíl para que compare contra el **árbol de trabajo** y no contra `HEAD`: así avisa
      **mientras se escribe**, que es cuando sirve. Antes reportaba `0/0` con los cambios sin commitear
      delante — inútil justo en el momento de la verdad. En un checkout limpio de CI el resultado es
      idéntico

### T4 — Compilación

- [x] `./nuvioes/gradle.sh :app:assembleFullDebug` → **`BUILD SUCCESSFUL in 6m 56s`** (primer build, con
      los builds nativos de CMake para 4 ABIs) y **`2m 37s`** el incremental
- [x] Keystore local generada con los valores por defecto de upstream (`../nuviotv.jks`, alias `nuviotv`),
      ignorada por git

### T5 — Verificación en dispositivo (Android TV arm64, SDK 36)

Ciclo completo con `mobile-mcp`, leyendo el atributo de foco del árbol de accesibilidad:

| Paso | Resultado |
| --- | --- |
| Arranque | Home con contenido |
| `DPAD_LEFT` desde el contenido | El foco entra al sidebar (Home, y=196) |
| `DPAD_DOWN` | Foco en **Live TV** (y=278) — el ítem está segundo, justo debajo de Home |
| `ENTER` | **Abre nuestra pantalla** |
| Estado vacío | `"No channels yet"` + `"Channels come from the addons you already have installed."` |
| **Foco al entrar** | `@e4 View at=417,385 size=518x64 focused` → **el foco está en el botón** |
| `DPAD_LEFT` | Alcanza el sidebar de vuelta → **no es un callejón sin salida** |
| `DPAD_RIGHT` | Vuelve al botón |
| `ENTER` en el botón | **Abre el gestor de addons de upstream** (`Addons`, `Install addon`, `Manage from phone`) |

---

## Hallazgo en vuelo: la primera versión de mi pantalla tenía el bug que este proyecto existe para arreglar

La primera pasada dejó el estado vacío **sin ningún elemento enfocable**. Verificado en el dispositivo:
`foco: []`, y las cuatro direcciones del D-pad no hacían absolutamente nada. El sidebar **tampoco** era
alcanzable. Único camino de salida: el botón Atrás.

Eso es exactamente el bloqueante `A4` que le señalé al fork de referencia (*"la pantalla no pide foco al
entrar"*). Habría sido indefendible entregarlo acá. Se corrigió antes de commitear:

- El estado vacío ahora tiene **una acción real y que funciona**: *"Manage addons"* → `Screen.AddonManager`.
  No es un botón decorativo: es el flujo real (no hay canales → revisá tus addons) y encaja con `RF-51`
- Se pide foco explícitamente con `FocusRequester` + `LaunchedEffect(Unit)`, siguiendo el patrón que ya usa
  `SearchScreen.kt:492`. **Nunca se deja el foco al orden de composición** (contrato §7.1 del PRD)

**Lección de método:** una pantalla "vacía" no es una pantalla trivial en un dispositivo con control
remoto. Sin un nodo enfocable no hay navegación posible, y el defecto se ve recién al probarlo. El
guardarraíl del presupuesto no lo detecta; el dispositivo sí.

---

## Criterio de cierre de F1

1. La pantalla abre desde el menú lateral. ✅
2. El guardarraíl de CI queda en verde con **exactamente 4 archivos de upstream**. ✅
3. El proyecto compila. ✅
4. Los textos viven en archivos propios, no en los de upstream. ✅
5. La pantalla no es un callejón sin salida y tiene foco inicial declarado. ✅

**F1 cerrado.**

---

## Evidencia: commits

| SHA | Commit |
| --- | --- |
| *(ver git log)* | `feat(livetv): add the isolated Live TV package with a real empty state` |
| | `chore(livetv): wire the four upstream integration hooks` |
| | `fix(nuvioes): measure the conflict budget against the working tree` |

---

## Pendiente antes de F2

- Decidir si se mantiene el `UPSTREAM_BASE` expuesto en el `versionName` (el PRD lo menciona en §9.2).
  Hoy el `versionName` es el de upstream (`1.1.0-beta.2`), así que un usuario no puede distinguir nuestra
  build de la oficial. Es un cambio en `app/build.gradle.kts`, es decir **suma a la superficie de enganche**:
  hay que decidirlo a conciencia.
