#!/usr/bin/env bash
set -euo pipefail

mkdir -p /app/logs /tmp/recoverymesh-primary /tmp/recoverymesh-secondary /tmp/rm-primary-sock /tmp/rm-secondary-sock

log(){ printf '[recoverymesh-runtime] %s\n' "$*"; }

wait_port(){
  local port="$1"; local name="$2"; local retries="${3:-90}"
  for ((i=1;i<=retries;i++)); do
    if nc -z 127.0.0.1 "$port" >/dev/null 2>&1; then log "$name ready on 127.0.0.1:$port"; return 0; fi
    sleep 1
  done
  log "ERROR: $name did not become ready"; return 1
}

PG_BIN="$(dirname "$(find /usr/lib/postgresql -type f -name initdb | sort | tail -n 1)")"
[[ -n "$PG_BIN" ]] || { log "ERROR: PostgreSQL binaries not found"; exit 1; }

init_cluster(){
  local dir="$1"
  if [[ ! -f "$dir/PG_VERSION" ]]; then
    rm -rf "$dir"
    install -d -o postgres -g postgres "$dir"
    su -s /bin/bash postgres -c "$PG_BIN/initdb -D '$dir' --auth=trust --no-locale --encoding=UTF8" >/dev/null
  fi
}

start_cluster(){
  local dir="$1"; local port="$2"; local sock="$3"; local logfile="$4"; local name="$5"
  install -d -o postgres -g postgres "$sock"
  touch "$logfile" && chown postgres:postgres "$logfile"
  su -s /bin/bash postgres -c "$PG_BIN/pg_ctl -D '$dir' -o \"-h 127.0.0.1 -p $port -k $sock\" -l '$logfile' start"
  wait_port "$port" "$name" 120
  if ! "$PG_BIN/psql" -h 127.0.0.1 -p "$port" -U postgres -Atqc "select 1 from pg_database where datname='recoverymesh'" | grep -q 1; then
    "$PG_BIN/createdb" -h 127.0.0.1 -p "$port" -U postgres recoverymesh
  fi
}

init_cluster /tmp/recoverymesh-primary
init_cluster /tmp/recoverymesh-secondary
start_cluster /tmp/recoverymesh-primary 5432 /tmp/rm-primary-sock /app/logs/primary-postgres.log primary-postgres
start_cluster /tmp/recoverymesh-secondary 5433 /tmp/rm-secondary-sock /app/logs/secondary-postgres.log secondary-postgres

export PRIMARY_DB_URL=jdbc:postgresql://127.0.0.1:5432/recoverymesh
export PRIMARY_DB_USER=postgres
export PRIMARY_DB_PASSWORD=postgres
export SECONDARY_DB_URL=jdbc:postgresql://127.0.0.1:5433/recoverymesh
export SECONDARY_DB_USER=postgres
export SECONDARY_DB_PASSWORD=postgres
export REPLICATION_DELAY_MS=250
export FAILOVER_DELAY_MS=1800

JAVA_LIMITS="-Xms48m -Xmx320m -XX:+UseSerialGC"
java $JAVA_LIMITS -jar /app/recoverymesh.jar >/app/logs/recoverymesh.log 2>&1 &
APP_PID=$!
wait_port 8080 recoverymesh 120

cleanup(){
  set +e
  kill "$APP_PID" 2>/dev/null || true
  su -s /bin/bash postgres -c "$PG_BIN/pg_ctl -D /tmp/recoverymesh-primary stop -m fast" >/dev/null 2>&1 || true
  su -s /bin/bash postgres -c "$PG_BIN/pg_ctl -D /tmp/recoverymesh-secondary stop -m fast" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

log "RecoveryMesh ready: http://127.0.0.1:8080"
wait "$APP_PID"
