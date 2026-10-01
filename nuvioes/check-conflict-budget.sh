#!/usr/bin/env bash
#
# Guardarraíl del presupuesto de conflicto con upstream (PRD NuvioES, sección 7.3).
#
# Falla si la diferencia contra el pin de upstream toca archivos no permitidos,
# o si la superficie de enganche supera 4 archivos / 40 líneas.
#
# Uso:  ./nuvioes/check-conflict-budget.sh [tag-base]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_TAG="$(tr -d '[:space:]' < UPSTREAM_BASE 2>/dev/null || echo '?')"

# Ref contra la que medimos NUESTRO delta. Por defecto, el pin de upstream.
#
# Durante un sync hay que pasar el tag destino: el pin todavía no se movió, así que
# medir contra el pin viejo cuenta TODA la deriva de upstream como si fuera nuestra
# y el guardarraíl falla con cientos de archivos ajenos.
DIFF_BASE="${1:-upstream-pin}"
if ! git rev-parse -q --verify "$DIFF_BASE" >/dev/null 2>&1; then
  echo "ERROR: no existe la ref '$DIFF_BASE'." >&2
  echo "       Por defecto se compara contra el tag upstream-pin." >&2
  echo "       Creálo con: git tag -f upstream-pin \$(cat UPSTREAM_BASE)" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Archivos de upstream que SÍ podemos modificar (los enganches). Exactos,
# uno por uno, para que agregar un quinto sea una decisión visible.
# ---------------------------------------------------------------------------
ALLOWED_UPSTREAM_FILES=(
  "app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt"
  "app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt"
  "app/src/main/java/com/nuvio/tv/MainActivity.kt"
  "app/build.gradle.kts"
  "app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsScreen.kt"
)

# Los limites subieron de 4/40 a 5/60 el 2026-09-29, con aprobacion explicita del dueno, para
# poner los ajustes de la feature donde estan los de la app original (categoria en
# SettingsScreen) en vez de un panel interno.
#
# Subieron de 60 a 80 el 2026-10-01, con aprobacion explicita del dueno (pidio la seccion
# "Partidos" en el riel lateral: "lo quiero en su propia seccion, no dentro de tv en vivo"),
# para promocionar la vista de partidos a destino propio. Costo medido: 74 lineas
# (Screen.kt 2, NuvioNavHost.kt 10, MainActivity.kt 30, build.gradle.kts 14,
# SettingsScreen.kt 18). Los archivos siguen siendo los mismos 5.
#
# La disciplina sigue igual: subir el techo otra vez es una decision aparte, no un efecto
# secundario de agregar una pantalla.
MAX_UPSTREAM_FILES=5
MAX_UPSTREAM_LINES=80

# ---------------------------------------------------------------------------
# Archivos y directorios PROPIOS. Nada de comodines amplios: upstream también
# tiene scripts/ y docs/, así que se enumera exactamente lo nuestro.
# ---------------------------------------------------------------------------
is_ours() {
  case "$1" in
    UPSTREAM_BASE) return 0 ;;
    NUVIO-ES.md) return 0 ;;
    nuvioes/*) return 0 ;;
    .github/workflows/upstream-drift.yml) return 0 ;;
    .github/workflows/conflict-budget.yml) return 0 ;;
    odd/tasks/*) return 0 ;;
    patches/*) return 0 ;;
    app/src/main/java/com/nuvio/tv/ext/livetv/*) return 0 ;;
    app/src/test/java/com/nuvio/tv/ext/livetv/*) return 0 ;;
    app/src/androidTest/java/com/nuvio/tv/ext/livetv/*) return 0 ;;
    app/src/main/res/values*/strings_livetv.xml) return 0 ;;
    app/src/main/res/layout/livetv_*) return 0 ;;
    app/src/main/res/drawable/livetv_*) return 0 ;;
    app/src/main/res/raw/livetv_*) return 0 ;;
    *) return 1 ;;
  esac
}

RC=0
TOUCHED=0
VIOLATIONS=()

# Se compara contra el ÁRBOL DE TRABAJO, no contra HEAD.
#
# `git diff <ref>` (sin `..HEAD`) incluye lo que todavía no está commiteado, así que
# el guardarraíl avisa MIENTRAS escribís, que es cuando importa. En un checkout
# limpio de CI el resultado es idéntico a comparar contra HEAD.
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if is_ours "$f"; then continue; fi
  if printf '%s\n' "${ALLOWED_UPSTREAM_FILES[@]}" | grep -qxF "$f"; then
    TOUCHED=$((TOUCHED + 1))
    continue
  fi
  VIOLATIONS+=("$f")
  RC=1
done < <(git diff --name-only "$DIFF_BASE")

LINES=0
for f in "${ALLOWED_UPSTREAM_FILES[@]}"; do
  n="$(git diff --numstat "$DIFF_BASE" -- "$f" | awk '{s+=$1+$2} END {print s+0}')"
  LINES=$((LINES + n))
done

echo "UPSTREAM_BASE (pin nominal): $BASE_TAG"
echo "Comparando contra:           $DIFF_BASE ($(git rev-parse --short "$DIFF_BASE" 2>/dev/null || echo '?'))"
echo

if [ "${#VIOLATIONS[@]}" -gt 0 ]; then
  echo "FUERA DE PRESUPUESTO: archivos de upstream modificados sin autorización:"
  printf '  - %s\n' "${VIOLATIONS[@]}"
  echo
  echo "Si de verdad hace falta tocar uno, es una decisión explícita: agregalo a"
  echo "ALLOWED_UPSTREAM_FILES en este script y justificá por qué en NUVIO-ES.md."
  echo
fi

echo "Superficie de enganche: $TOUCHED/$MAX_UPSTREAM_FILES archivos, $LINES/$MAX_UPSTREAM_LINES líneas"

if [ "$TOUCHED" -gt "$MAX_UPSTREAM_FILES" ]; then
  echo "FUERA DE PRESUPUESTO: $TOUCHED archivos de upstream (máximo $MAX_UPSTREAM_FILES)"
  RC=1
fi

if [ "$LINES" -gt "$MAX_UPSTREAM_LINES" ]; then
  echo "FUERA DE PRESUPUESTO: $LINES líneas en archivos de upstream (máximo $MAX_UPSTREAM_LINES)"
  RC=1
fi

if [ "$RC" -eq 0 ]; then
  echo "Presupuesto OK."
fi

exit "$RC"
