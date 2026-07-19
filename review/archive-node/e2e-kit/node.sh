#!/usr/bin/env bash
# Launch/stop the archive-enabled private-chain FullNode.
set -u
BASE="$(cd "$(dirname "$0")" && pwd)"
JAR="$BASE/FullNode.jar"
OUT="$BASE/node-dir"
LOG="$BASE/node.log"
PIDF="$BASE/node.pid"

start() {
  mkdir -p "$OUT"
  cd "$BASE"
  nohup java -Xmx3g -XX:+UseG1GC \
    -Dtron.arch.skipJavaCheck=true \
    -Dtron.archive.allowNonArm64=true \
    -jar "$JAR" -c "$BASE/private-archive.conf" --witness \
    -d "$OUT" >>"$LOG" 2>&1 &
  echo $! > "$PIDF"
  echo "started pid $(cat "$PIDF")"
}

stop_graceful() {
  [ -f "$PIDF" ] || { echo "no pid"; return 0; }
  local pid; pid=$(cat "$PIDF")
  kill -TERM "$pid" 2>/dev/null || true
  for _ in $(seq 1 60); do
    kill -0 "$pid" 2>/dev/null || { echo "stopped"; return 0; }
    sleep 1
  done
  echo "TERM timeout; killing"; kill -9 "$pid" 2>/dev/null || true
}

kill9() {
  [ -f "$PIDF" ] || { echo "no pid"; return 0; }
  kill -9 "$(cat "$PIDF")" 2>/dev/null || true
  echo "SIGKILL sent"
}

case "${1:-}" in
  start) start ;;
  stop) stop_graceful ;;
  kill9) kill9 ;;
  *) echo "usage: $0 start|stop|kill9"; exit 2 ;;
esac
