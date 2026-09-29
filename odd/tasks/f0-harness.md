# F0 — Harness de sincronización con upstream

**Estado:** en curso
**Rama:** `feat/f0-harness`
**PRD:** `docs/03-PRD-nuvio-es.md` §9-10 (repo hermano `../nuvio/`)
**Objetivo de la fase:** que actualizar a un tag nuevo de upstream sea barato, medible y detectado a tiempo.

---

## Por qué esta fase va primero

Sin harness, cada commit posterior es deuda de rebase acumulándose. La feature se escribe **después** de que
el presupuesto de 4 archivos y 40 líneas esté probado como sostenible, no antes.

---

## Tareas

### T1 — Pin y modelo de ramas

- [x] Clonar upstream y renombrar el remoto a `upstream`
- [x] Congelar el pin en `1.1.0-beta.2` (tag `d8c50017`); no hay `1.1.0` estable todavía
- [x] Crear `nuvioes` desde el tag y el tag `upstream-pin`
- [x] Escribir `UPSTREAM_BASE`
- [x] Identidad de commits como `rstoute` (local al repo), sin pie de IA

**Evidencia:** `UPSTREAM_BASE` = `1.1.0-beta.2`; `upstream-pin` = `d8c50017`.

### T2 — Guardarraíl del presupuesto

- [x] `nuvioes/check-conflict-budget.sh` — falla si se toca un archivo de upstream fuera de la lista, o si
      se superan 4 archivos / 40 líneas
- [x] Lista blanca **exacta** de nuestros archivos (nada de comodines amplios: upstream también tiene
      `scripts/` y `docs/`)
- [x] Verificado que **falla** al tocar a propósito un archivo de upstream no permitido
      (`AndroidManifest.xml` → exit 1, con el archivo listado)
- [x] Verificado que un enganche permitido **se cuenta** en vez de fallar
      (`Screen.kt` +2 líneas → `1/4 archivos, 2/40 líneas`, exit 0)

### T3 — Script de sincronización

- [x] `nuvioes/sync-upstream.sh` — `rebase --onto <tag-nuevo> <tag-viejo>`, respaldo de `nuvioes`, rama
      desechable, verificación del presupuesto, bump de `UPSTREAM_BASE`
- [x] **Ensayado de punta a punta contra deriva real de upstream**: `rebase --onto upstream/dev 1.1.0-beta.2`
      reaplicó nuestros commits sobre **85 commits** de avance y salió **limpio**
- [x] Añadida `SYNC_BRANCH` (default `nuvioes`) para poder ensayar el rebase sin tocar `nuvioes`
- [x] Tres guardas: la rama origen existe, tiene commits propios encima del pin, y el script del
      presupuesto existe y es ejecutable

> **Dos bugs reales que solo aparecieron por ensayar el rebase.** Ninguno se veía leyendo el código.
> 1. `sync-upstream.sh` seguía llamando a `./scripts/check-conflict-budget.sh`, ruta anterior a mover el
>    tooling fuera del `scripts/` ignorado. El rebase salía limpio y el paso siguiente abortaba.
> 2. Peor: el script rebaseaba `nuvioes` fijo. Corrido con el trabajo en una rama de feature, reaplicaba
>    **cero commits**, imprimía *"rebase LIMPIO"* y dejaba el árbol de upstream sin nada nuestro.
>    **Un no-op silencioso que parece éxito es el peor fallo posible para este script.**
> 3. Y el guardarraíl medía nuestro delta contra el pin **viejo**: durante un sync contaba toda la deriva
>    de upstream como si fuera nuestra, y fallaba con cientos de archivos ajenos. Habría bloqueado
>    **toda** sincronización legítima. Ahora la ref de comparación es un argumento explícito.

### T4 — CI: detección temprana de deriva

- [x] `.github/workflows/upstream-drift.yml` — diario: intenta el rebase contra el tag estable más nuevo,
      abre issue si falla, y busca señales de TV en vivo en upstream (`livetv|iptv|epg|xmltv`)
- [x] `.github/workflows/conflict-budget.yml` — en cada PR: presupuesto + gitleaks

### T5 — Entorno de compilación

- [x] `nuvioes/gradle.sh` — wrapper que resuelve el JDK sin tocar `gradle.properties` de upstream (0 archivos)
- [x] Verificado que Gradle arranca: **Gradle 8.13, JVM 21.0.12.1**, `JAVA_HOME` resuelto solo
- [x] Verificado `./nuvioes/gradle.sh tasks` → **`BUILD SUCCESSFUL in 1m 13s`**, con flavors `full`/`playstore`,
      variantes, tareas de lint y de test unitario

### T6 — Documentación del repositorio

- [x] `NUVIO-ES.md` — qué es este fork, la regla del presupuesto, el modelo de ramas, cómo compilar
- [x] Documentada la trampa del `.gitignore` de upstream (ver abajo)

### T7 — Esquivar el `.gitignore` de upstream

Upstream ignora `scripts/*`, `docs/`, `.mcp.json`, `AGENTS.md`, `CLAUDE.md` y varios más, y después
re-habilita con `!` **sólo sus propios archivos**. Un script nuestro en `scripts/` **se ignora en silencio y
nunca se commitea**: el harness parece completo y no está versionado.

- [x] Detectado con `git check-ignore -v`
- [x] Todo el tooling propio movido a `nuvioes/`, un directorio que upstream no ignora
- [x] Verificado que `nuvioes/*.sh`, `NUVIO-ES.md`, `UPSTREAM_BASE`, `odd/`, `patches/` y
      `.github/workflows/` **no** están ignorados
- [x] Verificado que los 3 scripts quedaron ejecutables (`chmod +x`) — el fork de referencia perdió el bit
      de ejecución en 10 archivos y rompió `./gradlew` en clones limpios

---

## Criterio de cierre de F0

1. `./nuvioes/check-conflict-budget.sh` corre y da OK con 0 archivos / 0 líneas. ✅
2. El mismo script **falla** cuando se modifica a propósito un archivo de upstream no permitido. ✅
   (`AndroidManifest.xml` → exit 1, archivo listado; y un enganche permitido → contado, exit 0)
3. `./nuvioes/gradle.sh --version` arranca (JDK resuelto sin exportar nada a mano). ✅
4. Los dos workflows están commiteados y son válidos. ✅
5. `./nuvioes/sync-upstream.sh` ensayado sobre deriva real de upstream: rebase limpio, presupuesto OK,
   `UPSTREAM_BASE` y pin actualizados. ✅

**F0 cerrado.**

---

## Evidencia: commits

Rama `feat/f0-harness`, base `upstream-pin` = `d8c500175` (tag `1.1.0-beta.2`). Autor de todos:
`lordmacu`, sin pie de IA.

| SHA | Commit |
| --- | --- |
| `2e46f7046` | `chore: pin upstream base and add the conflict-budget guardrail` |
| `688c71f18` | `chore: add upstream sync tooling and drift detection CI` |
| `5f8315119` | `chore: add the JDK wrapper and document the fork model` |
| `0ac6d9655` | `fix(nuvioes): correct stale script paths after moving tooling to nuvioes/` |
| `59e124a22` | `fix(nuvioes): make sync-upstream.sh fail loudly instead of rebasing nothing` |
| `1be4d3649` | `fix(nuvioes): measure our delta against the target base, not the stale pin` |
| `2c515607d` | `docs(odd): close F0 with the rehearsal evidence and the bugs it found` |

Los últimos cuatro commits son consecuencia directa del ensayo del rebase. **Sin ensayar el camino feliz,
los tres bugs habrían llegado a F1 con el harness aparentando estar completo.**

## Pendiente antes de F1

- **Merge de `feat/f0-harness` a `nuvioes`** — lo decide el dueño.
- **Publicar**: crear el fork real en GitHub y pushear. Requiere OK explícito (es publicación).
  Ver «Pendiente de decisión del dueño» al final de este documento.

---

## Bitácora

| Fecha | Qué |
| --- | --- |
| 2026-09-28 | Arranque. Pin, identidad, `UPSTREAM_BASE`, tag `upstream-pin`. Escritos los 3 scripts, los 2 workflows y `NUVIO-ES.md`. |
| 2026-09-28 | Verificado el guardarraíl en sus dos direcciones (falla con violación, cuenta el enganche permitido). Detectada y esquivada la trampa del `.gitignore` de upstream: el tooling se movió de `scripts/` a `nuvioes/`. |
| 2026-09-28 | **Ensayado el rebase real** contra `upstream/dev`: 85 commits de avance, rebase limpio. El ensayo destapó 3 bugs que no se veían leyendo el código (ruta stale, no-op silencioso, base de comparación equivocada). Gradle verificado: `BUILD SUCCESSFUL`. **F0 cerrado.** |
| 2026-09-29 | Publicado: fork `esenciable/NuvioES`. Rama de integración renombrada a `nuvioes` para no colisionar con el `main` de upstream. Autoría reescrita a `rstoute`. Documentos convertidos de voseo a tuteo. Job de deriva anclado a `ref: nuvioes`. |
| 2026-09-29 | Ejecutado el job de deriva: **2 bugs más** encontrados y corregidos (selección de tag y canal de aviso muerto). 3 ejecuciones verificadas. **F0 cerrado del todo.** |

---

## Decisiones del dueño (resueltas)

- ~~**P1** — bajo qué cuenta publicar.~~ **Resuelto:** [`esenciable/NuvioES`](https://github.com/esenciable/NuvioES),
  fork real de `NuvioMedia/NuvioTV`, público.
- **Identidad de commits:** `rstoute <249775880+rstoute@users.noreply.github.com>`.
  Los commits originales se reescribieron con `git filter-branch` (nada estaba publicado, así que fue gratis).
- **Rama de integración:** **`nuvioes`**, no `main`. Upstream tiene su propio `main`, así que reusar ese
  nombre hacía que el botón *"Sync fork"* ofreciera **descartar nuestros commits** y que un push nuestro
  fuera **rechazado por non-fast-forward**. Además es la rama por defecto del repositorio, así que un job
  programado nunca hace checkout de `dev` por accidente.
- ~~**Merge de `feat/f0-harness`**~~ **Hecho**, fast-forward.
- ~~**Publicar**~~ **Hecho**: rama `nuvioes` + tag `upstream-pin` pusheados; `origin` y `upstream` configurados.

## Pendiente antes de F1 (resuelto)

- [x] ~~Ensayar el job `upstream-drift` una vez~~ **Hecho**, y valió la pena: al ejecutarlo aparecieron
      **dos bugs más**, ninguno visible leyendo el workflow.

### Los dos bugs que solo aparecieron al ejecutar el job de deriva

**1. Selección de tag equivocada.** Elegía el tag estable más nuevo filtrando `^X.Y.Z$`, lo que **excluye
nuestro propio pin** (`1.1.0-beta.2`). Terminaba eligiendo `1.0.0`, que es más **viejo**, y rebaseaba
**hacia atrás**: un resultado sin ningún sentido, presentado como verde.

**2. El aviso nunca podía funcionar.** Los forks tienen los *issues* **deshabilitados** por defecto. El paso
que abría el issue habría fallado justo al intentar avisar: un job de detección con el canal de
notificación muerto.

Y un tercero, al re-ejecutar el primer arreglo: **filtrar sólo por `^X.Y.Z$` tampoco alcanza**, pero
`--sort=-v:refname` a secas pone primero los tags que **no son versiones** — nuestro propio `upstream-pin` —
así que "el tag más nuevo" era nuestro tag y el job rebaseaba sobre sí mismo informando *"0 commits"*.

**Solución**: filtro `^v?[0-9]+\.[0-9]+\.[0-9]+` (incluye pre-releases, excluye tags que no son versiones);
el resultado va **siempre** al resumen del job; el job **falla** cuando hay conflicto o señales de TV en vivo
—un job programado que falla notifica al dueño, y eso no depende de ninguna configuración del repositorio—;
y el issue queda como intento *best-effort* (con los issues ya habilitados en el repo).

**Verificado**: tres ejecuciones. La última reporta **sin deriva** y saltea correctamente los pasos de aviso,
que es el comportamiento correcto porque el pin ya está en el tag más nuevo.

> **Patrón que se repite en todo el harness**: los tres bugs de F0 y estos dos se encontraron **ejecutando**,
> nunca leyendo. Un harness sin ensayar es una promesa, no una garantía.

## F0 — cerrado y verificado

- Pin, modelo de ramas, guardarraíl, script de sincronización, detección de deriva y wrapper de Gradle:
  todo en pie y ensayado.
- Publicado en `esenciable/NuvioES`, rama `nuvioes`.
- **Sigue F1.**
