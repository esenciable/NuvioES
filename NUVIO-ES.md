# NuvioES — fork de [NuvioTV](https://github.com/NuvioMedia/NuvioTV) con TV en vivo

> **Estado: F0 (harness) cerrado y publicado — todavía no hay feature.** Este repositorio es la base
> sobre la que se construye la feature de TV en vivo y guía de programación, con una restricción de
> diseño no negociable: **tiene que seguir siendo barato recibir las mejoras de upstream.**

| | |
| --- | --- |
| Repositorio | [`esenciable/NuvioES`](https://github.com/esenciable/NuvioES) — fork real de `NuvioMedia/NuvioTV` |
| Rama de integración | **`nuvioes`** (es la rama por defecto del repositorio) |
| Pin de upstream | `1.1.0-beta.2` (`d8c50017`), en el archivo `UPSTREAM_BASE` y en el tag `upstream-pin` |
| Remotos | `origin` = este fork · `upstream` = `NuvioMedia/NuvioTV` |

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
> no ignora. Si agregas tooling, verifica antes con `git check-ignore -v <ruta>`.

> **Por qué los textos van en `strings_livetv.xml` y no en `strings.xml`:** Android fusiona *todos* los
> `.xml` de `res/values/`. Un archivo propio da cero conflictos de traducción al actualizar, aunque la
> feature agregue cientos de cadenas en varios idiomas. El fork de referencia las metió dentro de los
> archivos de upstream y ahora arrastra cuatro archivos de conflicto solo por textos.

---

## Modelo de ramas

> **¿Por qué la rama de integración se llama `nuvioes` y no `main`?** Porque **upstream tiene su propia
> rama `main`**. Reusar ese nombre crea dos peligros concretos: el botón *"Sync fork"* de GitHub ofrecería
> **descartar nuestros commits**, y un push nuestro sería **rechazado por non-fast-forward** (el fork ya
> tiene el `main` de upstream, que es otra línea). Un nombre que upstream no tiene elimina los dos de raíz.
> Además, la rama por defecto del repositorio está fijada a `nuvioes`, así que un job programado nunca
> hace checkout de `dev` por accidente.

```text
upstream/dev              ──●──●──●──●──●──●──●     avanza solo
                                │
                    1.1.0 ──────┤  TAG: pin anterior (UPSTREAM_BASE)
                    1.2.0 ──────┤  TAG: pin nuevo
                                │
nuvioes               tag1 ─────┼──●──●──●          nuestros commits
                                │
                      rebase --onto tag2 tag1 nuvioes
```

**Nunca se mergea `upstream/dev` dentro de `nuvioes`.** Siempre:

```bash
./nuvioes/sync-upstream.sh --tag-latest
```

El script respalda `nuvioes`, rebasea sobre una rama desechable, verifica el presupuesto y recién entonces
te dice cómo avanzar. Si hay conflictos, los lista y no toca `nuvioes`.

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

## Versionado (y por qué no se deja el de upstream)

`versionName` **no es cosmético**: el updater in-app compara el SemVer del tag de release contra
`BuildConfig.VERSION_NAME`, y **el `versionCode` no participa de la decisión**
(`app/src/main/java/com/nuvio/tv/updater/`). Si dejáramos el de upstream, una build instalada
volvería a recibir la oferta de la misma release **para siempre**.

```kotlin
val nuvioesBuild = 1
versionCode = 1065 * 100 + nuvioesBuild
versionName = "1.1.0-beta.2-nuvioes.$nuvioesBuild"
```

### La forma del sufijo, y por qué es esa

| Forma | ¿Sirve? | Por qué |
| --- | --- | --- |
| `<base>-nuvioes.<n>` | **Sí** | Pasa el regex de `VersionUtils`; ordena **por encima** de la base (el identificador alfanumérico `2-nuvioes` tiene más precedencia que el numérico `2`) y crece con `n` |
| `<base>+nuvioes.<n>` | **No** | `VersionUtils` **descarta el build metadata** en `parse()`. Es correcto según SemVer, pero como marcador es inútil |

Funciona igual con una base estable (`1.1.0-nuvioes.1`), sin condicionales.

### Reglas

1. **Al subir el pin de upstream hay que actualizar las dos constantes** (`1065` y `"1.1.0-beta.2"`).
   Eso es **a propósito**: quedan como literales en un archivo de upstream, así que el salto de versión
   **produce un conflicto visible** en el rebase y obliga a un cambio consciente en vez de arrastrar un
   número viejo en silencio. Es un punto de conflicto esperado, no un accidente.
2. **`nuvioesBuild` incrementa en cada release nuestra** sobre la misma base. No toques `versionName`
   sin tocar `versionCode`: el mismo número alimenta a los dos.
3. **El tag de release debe espejar la estabilidad de la base** (prerelease si la base lo es).
   `UpdateChannel.defaultForVersion` pone el canal en BETA para versiones prerelease, y en canal STABLE
   los prereleases **no son elegibles**: un tag no-prerelease sobre base prerelease dejaría a los
   usuarios de STABLE sin ver la actualización.
4. **`VERSION_NAME` viaja a terceros**: `SupabaseModule` arma `User-Agent: "NuvioTV/<VERSION_NAME>"` y
   también lo usan `NetworkModule`, `MdbListModule`, `DeviceSessionRegistration` y Sentry. Por eso el
   sufijo debe seguir siendo **SemVer válido**.
5. **`versionCode = base × 100 + n`**: monotónico entre bases mientras `n < 100`, y no se cruza con nada.
   La alternativa `base + n` se rompe cuando upstream salta y nosotros ya veníamos sumando.

> El About de la app ya muestra `BuildConfig.VERSION_NAME`, así que esto se ve **sin tocar
> `AboutScreen.kt`** — que habría sido un quinto enganche.

---

## Baseline de tests

**El pin de upstream no viene verde.** Medido en el pin puro, en un worktree aparte: `1.1.0-beta.2`
(`d8c500175`) tiene **1.606 tests con 18 fallos**. Los nombres exactos están en
`nuvioes/known-test-failures.txt`.

Importa porque, al rebasar, **una suite roja es lo esperado** y no debe confundirse con una regresión
nuestra. La comparación correcta es contra ese archivo, no contra cero:

| | Tests | Fallos |
| --- | --- | --- |
| Pin `d8c500175`, sin nada nuestro | 1.606 | **18** |
| Nuestro árbol | 1.657 | **18** (los mismos 18) |

```bash
# Medir el baseline de un tag nuevo, sin tocar el árbol de trabajo
git worktree add /tmp/baseline <tag>
cp local.properties nuviotv.jks /tmp/baseline/
(cd /tmp/baseline && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testFullDebugUnitTest --console=plain)
# resultados en /tmp/baseline/app/build/test-results/testFullDebugUnitTest/
git worktree remove --force /tmp/baseline

# Y nuestros tests solos, que sí tienen que estar siempre verdes:
./nuvioes/gradle.sh :app:testFullDebugUnitTest --tests 'com.nuvio.tv.ext.livetv.*'
```

No es sólo un entorno exótico: hay **drift propio de upstream**. `PluginBinaryFetchTest` llama a
`PluginRuntime.performNativeFetch(...)` con una **firma que no existe** (`NoSuchMethodException`), y el
mock de `CatalogRepositoryTypeTest` no stubbea `getCustomPosterEnabledScreens()`, un método que su propio
`CatalogRepositoryImpl` ya invoca. Es material para un PR a upstream.

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
