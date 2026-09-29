#!/usr/bin/env bash
#
# Guardarraíl del presupuesto de conflicto con upstream (PRD NuvioES, sección 7.3).
#
# Falla si la diferencia contra el pin de upstream toca archivos no permitidos,
# o si la superficie de enganche supera 4 archivos / 40 líneas.
#
# Uso:  ./scripts/check-conflict-budget.sh [tag-base]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_TAG="${1:-$(tr -d '[:space:]' < UPSTREAM_BASE)}"
PIN_REF="upstream-pin"

if ! git rev-parse -q --verify "$PIN_REF" >/dev/null 2>&1; then
  echo "ERROR: falta el tag $PIN_REF (marca el commit de upstream sobre el que construimos)." >&2
  echo "       Creálo con: git tag -f $PIN_REF $(cat UPSTREAM_BASE)" >&2
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
)

MAX_UPSTREAM_FILES=4
MAX_UPSTREAM_LINES=40

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
    app/src/main/res/values*/strings_livetv.xml) return 0 ;;
    app/src/main/res/drawable/livetv_*) return 0 ;;
    app/src/main/res/raw/livetv_*) return 0 ;;
    *) return 1 ;;
  esac
}

RC=0
TOUCHED=0
VIOLATIONS=()

while IFS= read -r f; do
  [ -z "$f" ] && continue
  if is_ours "$f"; then continue; fi
  if printf '%s\n' "${ALLOWED_UPSTREAM_FILES[@]}" | grep -qxF "$f"; then
    TOUCHED=$((TOUCHED + 1))
    continue
  fi
  VIOLATIONS+=("$f")
  RC=1
done < <(git diff --name-only "$PIN_REF"..HEAD)

LINES=0
for f in "${ALLOWED_UPSTREAM_FILES[@]}"; do
  n="$(git diff --numstat "$PIN_REF"..HEAD -- "$f" | awk '{s+=$1+$2} END {print s+0}')"
  LINES=$((LINES + n))
done

echo "Base de upstream (UPSTREAM_BASE): $BASE_TAG"
echo "Pin ($PIN_REF):                   $(git rev-parse --short "$PIN_REF" 2>/dev/null || echo '?')"
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
