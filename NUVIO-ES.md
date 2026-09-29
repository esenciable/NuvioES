# NuvioES — fork de [NuvioTV](https://github.com/NuvioMedia/NuvioTV) con TV en vivo

> **Estado: F0 (harness) — todavía no hay feature.** Este repositorio es la base sobre la que se
> construye la feature de TV en vivo y guía de programación, con una restricción de diseño no negociable:
> **tiene que seguir siendo barato recibir las mejoras de upstream.**

El análisis, la auditoría del fork de referencia y el PRD completo viven en el repositorio hermano
(`../nuvio/docs/`). Los documentos clave:

- `03-PRD-nuvio-es.md` — qué construimos y por qué, con el presupuesto de conflicto.
- `04-runbook-sync-upstream.md` — cómo se actualiza, paso a paso.
- `05-entorno-evaluacion-mobile-mcp.md` — cómo se prueban los criterios de aceptación en dispositivo.

---

## La regla que gobierna este repositorio

**La superficie de conflicto con upstream es la métrica principal del proyecto, no una métrica de producto.**

Upstream publica unos **300 commits cada 10 días**. Cualquier fork que diverja estructuralmente muere en
semanas. Por eso:

| Métrica | Límite | Verificado por |
| --- | --- | --- |
| Archivos de upstream modificados | **≤ 4** | `nuvioes/check-conflict-budget.sh` (falla el build) |
| Líneas enganchadas en archivos de upstream | **≤ 40** | ídem |
| Dependencias nuevas en `:app` | **0** | revisión |
| Binarios vendorizados | **0** | revisión |
| Credenciales en el repositorio | **0** | gitleaks en CI |

### Los 4 archivos de upstream que tocamos

| # | Archivo | Enlace |
| --- | --- | --- |
| 1 | `app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt` | la ruta `livetv` |
| 2 | `app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt` | 1 import + 1 `composable` que delega en `LiveTvRoute(navController)` |
| 3 | `app/src/main/java/com/nuvio/tv/MainActivity.kt` | el ítem del menú lateral |
| 4 | `app/build.gradle.kts` | el default propio del updater in-app |

**Todo lo demás es nuestro y vive aislado:**

```text
app/src/main/java/com/nuvio/tv/ext/livetv/**   ← todo el código de la feature
app/src/main/res/values*/strings_livetv.xml    ← textos, en archivo propio (nunca en el strings.xml de upstream)
app/src/main/res/drawable/livetv_*
app/src/main/res/raw/livetv_*
nuvioes/                                       ← gradle.sh, sync-upstream.sh, check-conflict-budget.sh
.github/workflows/                             ← upstream-drift.yml, conflict-budget.yml
odd/tasks/                                     ← documentos de trabajo
patches/                                       ← los enganches, re-aplicables
UPSTREAM_BASE                                  ← el tag de upstream sobre el que estamos
```

> **Cuidado con el `.gitignore` de upstream — es una trampa real.** Ignora `scripts/*`, `docs/`,
> `.mcp.json`, `AGENTS.md` y más, y después re-habilita con `!` sólo *sus propios* archivos. O sea que
> un archivo nuestro en `scripts/` **se ignora en silencio y nunca se commitea**: el harness parece
> completo y no está versionado. Por eso todo lo nuestro vive en `nuvioes/`, un directorio que upstream
> no ignora. Si agregás tooling, verificá antes con `git check-ignore -v <ruta>`.

> **Por qué los textos van en `strings_livetv.xml` y no en `strings.xml`:** Android fusiona *todos* los
> `.xml` de `res/values/`. Un archivo propio da cero conflictos de traducción al actualizar, aunque la
> feature agregue cientos de cadenas en varios idiomas. El fork de referencia las metió dentro de los
> archivos de upstream y ahora arrastra cuatro archivos de conflicto solo por textos.

---

## Modelo de ramas

```text
upstream/dev              ──●──●──●──●──●──●──●     avanza solo
                                │
                    1.1.0 ──────┤  TAG: pin anterior (UPSTREAM_BASE)
                    1.2.0 ──────┤  TAG: pin nuevo
                                │
main                  tag1 ─────┼──●──●──●          nuestros commits
                                │
                      rebase --onto tag2 tag1 main
```

**Nunca se mergea `upstream/dev` dentro de `main`.** Siempre:

```bash
./nuvioes/sync-upstream.sh --tag-latest
```

El script respalda `main`, rebasea sobre una rama desechable, verifica el presupuesto y recién entonces
te dice cómo avanzar. Si hay conflictos, los lista y no toca `main`.

---

## Compilar

El JDK **no está en el `PATH`** de esta máquina, y fijarlo en `gradle.properties` sería tocar un archivo
de upstream. Por eso hay un wrapper propio:

```bash
./nuvioes/gradle.sh tasks
./nuvioes/gradle.sh :app:assembleDebug
```

El wrapper detecta el JDK (Homebrew `openjdk@21` o el JBR de Android Studio) y llama al `gradlew` de
upstream. Coste de conflicto: **0 archivos de upstream**.

`local.properties` (ignorado por git, no cuenta en el presupuesto) necesita `sdk.dir`; el resto de las
claves son opcionales para compilar y **obligatorias para que la app hable con el backend**.

---

## Estado de las fases

| Fase | Descripción | Estado |
| --- | --- | --- |
| **F0** | Harness: pin, modelo de ramas, scripts de sync, guardarraíles de CI | **en curso** |
| F1 | Esqueleto `ext/livetv` + los 4 enganches + `strings_livetv.xml` | pendiente |
| F2 | Datos y EPG (addon propio, fuentes XMLTV, parser con topes) | pendiente |
| F3 | Interfaz: lista, panel de vista previa, pantalla completa, grilla EPG | pendiente |
| F4 | Calidad: filtro parental sin fugas, estados de error, presupuesto de rendimiento | pendiente |
| F5 | Promoción al tag `1.1.0` final, keystore propia, release firmada | pendiente |

---

## Licencia

NuvioTV es **GPL-3.0**. Este fork también lo es, y por ser un derivado **debe publicar su código fuente
completo bajo la misma licencia** y conservar los avisos de copyright.

Este proyecto **no está afiliado a NuvioMedia** ni es una versión oficial de Nuvio.
