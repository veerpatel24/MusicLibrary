#!/usr/bin/env bash
# Run the Music Library web UI + API. Terminal flow: use music.Driver.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  # Strip Windows CRLF so `source` never sees stray \r (fixes "command not found")
  if [[ "$(uname -s)" == "Darwin" ]]; then
    sed -i '' $'s/\r$//' .env 2>/dev/null || true
  else
    sed -i $'s/\r$//' .env 2>/dev/null || true
  fi
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
mkdir -p target/classes web/uploads
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)}"
CP="lib/cs112.jar:lib-web/gson-2.10.1.jar:target/classes"
if [[ ! -f lib-web/gson-2.10.1.jar ]]; then
  echo "Downloading Gson..."
  mkdir -p lib-web
  curl -fsSL -o lib-web/gson-2.10.1.jar \
    https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
fi
echo "Compiling..."
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$CP" -d target/classes src/music/*.java
echo "Starting server on http://localhost:7070 (project root: $ROOT)"
exec "$JAVA_HOME/bin/java" -cp "$CP" \
  -Dmusic.library.root="$ROOT" \
  -Dmusic.library.port="${PORT:-7070}" \
  music.WebApiServer
