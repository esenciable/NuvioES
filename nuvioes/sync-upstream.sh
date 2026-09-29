#!/usr/bin/env bash
#
# Sincroniza nuestra serie de parches sobre un tag nuevo de upstream.
# Ver docs/04-runbook-sync-upstream.md (en el repo de análisis) para el procedimiento completo.
#
#   ./nuvioes/sync-upstream.sh 1.2.0              # rebase en una rama desechable
#   ./nuvioes/sync-upstream.sh 1.2.0 --tag-latest # elige el tag estable más nuevo
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
git branch -f "$BACKUP" main
echo "==> Respaldo: $BACKUP"

WORK="sync/$TARGET_TAG-$STAMP"
git switch -c "$WORK" main

echo
echo "==> Rebase de nuestros commits sobre $TARGET_TAG"
if git rebase --onto "$TARGET_TAG" "$OLD_BASE" "$WORK"; then
  echo "==> Rebase LIMPIO"

  echo "==> Verificando presupuesto de conflicto"
  ./nuvioes/check-conflict-budget.sh "$TARGET_TAG"

  echo "$TARGET_TAG" > UPSTREAM_BASE
  git add UPSTREAM_BASE
  git commit -m "chore: bump upstream base to $TARGET_TAG"
  git tag -f "upstream-pin" "$WORK"

  echo
  echo "==> Listo. Revisá el diff y probá en dispositivo, después:"
  echo "      git switch main && git merge --ff-only $WORK"
  echo "      git tag -f upstream-pin"
  echo "      git push origin main --tags"
else
  echo
  echo "==> CONFLICTOS. Archivos:"
  git diff --name-only --diff-filter=U || true
  echo
  echo "Resolvé, y luego:  git rebase --continue"
  echo "Para abandonar:    git rebase --abort && git switch main && git branch -D $WORK"
  exit 1
fi

echo
echo "==> La rama $WORK queda local para inspección."
echo "    Para descartarla: git switch main && git branch -D $WORK"
