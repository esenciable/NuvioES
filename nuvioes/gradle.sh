#!/usr/bin/env bash
#
# Wrapper de Gradle para NuvioES.
#
# Por qué existe: el JDK tiene que estar disponible para Gradle, pero fijarlo en el
# `gradle.properties` del repositorio sería modificar un archivo de upstream y romper
# el presupuesto de conflicto del PRD (sección 7.3). Este wrapper vive en un archivo
# nuestro, así que cuesta 0.
#
# Uso:  ./scripts/gradle.sh tasks
#       ./scripts/gradle.sh :app:assembleDebug
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk@21" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/opt/homebrew/opt/openjdk"
  do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  echo "ERROR: no se encontró un JDK. Definí JAVA_HOME." >&2
  exit 1
fi

echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "==> ./gradlew $*"

cd "$REPO_ROOT"
exec ./gradlew "$@"
