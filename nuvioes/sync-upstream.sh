#!/usr/bin/env bash
#
# Sincroniza nuestra serie de parches sobre un tag nuevo de upstream.
# Ver docs/04-runbook-sync-upstream.md (en el repo de análisis) para el procedimiento completo.
#
#   ./nuvioes/sync-upstream.sh 1.2.0              # rebase en una rama desechable
#   ./nuvioes/sync-upstream.sh --tag-latest       # elige el tag estable más nuevo
#   SYNC_BRANCH=feat/x ./nuvioes/sync-upstream.sh 1.2.0   # ensayar sin tocar main
#
# Nunca mergea upstream/dev dentro de main: siempre rebase sobre un tag.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TARGET_TAG="${1:-}"
if [ -z "$TARGET_TAG" ]; then
  echo "uso: $0 <tag-upstream-nuevo|--tag-latest>" >&2
  exit 1
fi

if [ ! -f UPSTREAM_BASE ]; then
  echo "ERROR: falta UPSTREAM_BASE" >&2
  exit 1
fi
OLD_BASE="$(tr -d '[:space:]' < UPSTREAM_BASE)"

# Rama a sincronizar. Por defecto main. Overridable para poder ensayar el rebase
# sin tocar main:  SYNC_BRANCH=feat/x ./nuvioes/sync-upstream.sh <tag>
SRC_BRANCH="${SYNC_BRANCH:-main}"

git rev-parse --verify --quiet "refs/heads/$SRC_BRANCH" >/dev/null \
  || { echo "ERROR: no existe la rama $SRC_BRANCH" >&2; exit 1; }

# Guardas de cordura. Sin esto el script puede "tener éxito" rebaseando nada:
# si la rama no tiene commits propios encima del pin, el rebase es un no-op y el
# resultado es el árbol de upstream sin nuestro tooling.
PENDING="$(git rev-list --count "$OLD_BASE..$SRC_BRANCH")"
if [ "$PENDING" -eq 0 ]; then
  echo "ERROR: $SRC_BRANCH no tiene commits propios encima de $OLD_BASE." >&2
  echo "       No hay nada que sincronizar. Si tu trabajo está en otra rama, mergeala primero" >&2
  echo "       o pasa SYNC_BRANCH=<rama>." >&2
  exit 1
fi

BUDGET_SCRIPT="$REPO_ROOT/nuvioes/check-conflict-budget.sh"
if [ ! -x "$BUDGET_SCRIPT" ]; then
  echo "ERROR: falta $BUDGET_SCRIPT (o no es ejecutable)." >&2
  echo "       Recordá que upstream ignora scripts/*: nuestro tooling vive en nuvioes/." >&2
  exit 1
fi

echo "==> Trayendo upstream"
git fetch upstream --tags --prune

if [ "$TARGET_TAG" = "--tag-latest" ]; then
  TARGET_TAG="$(git tag -l --sort=-v:refname \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' \
    | head -1)"
  if [ -z "$TARGET_TAG" ]; then
    echo "No hay ningún tag estable en upstream todavía." >&2
    exit 0
  fi
  echo "==> Tag estable más nuevo: $TARGET_TAG"
fi

git rev-parse --verify --quiet "refs/tags/$TARGET_TAG" >/dev/null \
  || { echo "ERROR: no existe el tag $TARGET_TAG" >&2; exit 1; }

echo "==> Pin actual:   $OLD_BASE"
echo "==> Tag destino:  $TARGET_TAG"

if [ "$OLD_BASE" = "$TARGET_TAG" ]; then
  echo "Ya estamos sobre $TARGET_TAG. Nada que hacer."
  exit 0
fi

BEHIND="$(git rev-list --count "$OLD_BASE..$TARGET_TAG")"
echo "==> Upstream avanzó $BEHIND commits entre $OLD_BASE y $TARGET_TAG"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="backup/pre-sync-$STAMP"
git branch -f "$BACKUP" "$SRC_BRANCH"
echo "==> Respaldo: $BACKUP"

echo "==> Rama a sincronizar: $SRC_BRANCH ($PENDING commits propios)"

WORK="sync/$TARGET_TAG-$STAMP"
git switch -c "$WORK" "$SRC_BRANCH"

echo
echo "==> Rebase de nuestros commits sobre $TARGET_TAG"
if git rebase --onto "$TARGET_TAG" "$OLD_BASE" "$WORK"; then
  echo "==> Rebase LIMPIO"

  echo "==> Verificando presupuesto de conflicto"
  "$BUDGET_SCRIPT" "$TARGET_TAG"

  echo "$TARGET_TAG" > UPSTREAM_BASE
  git add UPSTREAM_BASE
  git commit -m "chore: bump upstream base to $TARGET_TAG"
  git tag -f "upstream-pin" "$WORK"

  echo
  echo "==> Listo. Revisá el diff y prueba en dispositivo, después:"
  echo "      git switch $SRC_BRANCH && git merge --ff-only $WORK"
  echo "      git tag -f upstream-pin"
  echo "      git push origin $SRC_BRANCH --tags"
else
  echo
  echo "==> CONFLICTOS. Archivos:"
  git diff --name-only --diff-filter=U || true
  echo
  echo "Resolvé, y luego:  git rebase --continue"
  echo "Para abandonar:    git rebase --abort && git switch $SRC_BRANCH && git branch -D $WORK"
  exit 1
fi

echo
echo "==> La rama $WORK queda local para inspección."
echo "    Para descartarla: git switch $SRC_BRANCH && git branch -D $WORK"
