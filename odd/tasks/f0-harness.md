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
- [x] Crear `main` desde el tag y el tag `upstream-pin`
- [x] Escribir `UPSTREAM_BASE`
- [x] Identidad de commits como `lordmacu` (local al repo), sin pie de IA

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

- [x] `nuvioes/sync-upstream.sh` — `rebase --onto <tag-nuevo> <tag-viejo>`, respaldo de `main`, rama
      desechable, verificación del presupuesto, bump de `UPSTREAM_BASE`
- [x] **Ensayado de punta a punta contra deriva real de upstream**: `rebase --onto upstream/dev 1.1.0-beta.2`
      reaplicó nuestros commits sobre **85 commits** de avance y salió **limpio**
- [x] Añadida `SYNC_BRANCH` (default `main`) para poder ensayar el rebase sin tocar `main`
- [x] Tres guardas: la rama origen existe, tiene commits propios encima del pin, y el script del
      presupuesto existe y es ejecutable

> **Dos bugs reales que solo aparecieron por ensayar el rebase.** Ninguno se veía leyendo el código.
> 1. `sync-upstream.sh` seguía llamando a `./scripts/check-conflict-budget.sh`, ruta anterior a mover el
>    tooling fuera del `scripts/` ignorado. El rebase salía limpio y el paso siguiente abortaba.
> 2. Peor: el script rebaseaba `main` fijo. Corrido con el trabajo en una rama de feature, reaplicaba
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

## Bitácora

| Fecha | Qué |
| --- | --- |
| 2026-09-28 | Arranque. Pin, identidad, `UPSTREAM_BASE`, tag `upstream-pin`. Escritos los 3 scripts, los 2 workflows y `NUVIO-ES.md`. |
| 2026-09-28 | Verificado el guardarraíl en sus dos direcciones (falla con violación, cuenta el enganche permitido). Detectada y esquivada la trampa del `.gitignore` de upstream: el tooling se movió de `scripts/` a `nuvioes/`. |
| 2026-09-28 | **Ensayado el rebase real** contra `upstream/dev`: 85 commits de avance, rebase limpio. El ensayo destapó 3 bugs que no se veían leyendo el código (ruta stale, no-op silencioso, base de comparación equivocada). Gradle verificado: `BUILD SUCCESSFUL`. **F0 cerrado.** |

---

## Pendiente de decisión del dueño

- **P1** — nombre y organización del fork real en GitHub. La cuenta activa del token es `esenciable`
  (3 repos), pero la identidad de commits acordada es `lordmacu` (239 repos). Hay que decidir bajo qué
  cuenta se publica, porque el token activo no puede crear repos en `lordmacu`.
- Crear el fork y pushear es una acción de publicación: **espera OK explícito**.
