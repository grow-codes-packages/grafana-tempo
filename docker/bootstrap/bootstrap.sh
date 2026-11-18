#!/bin/sh
# One-shot bootstrap for the Grafana Tempo SDK fixture (GRO-1379). POSIX sh, idempotent.
#
# Tempo has no users, tokens or schema, so the bootstrap only:
#   1. waits until both instances answer /ready,
#   2. seeds one known trace (2 services, 3 spans, event, link, error status) into the single-tenant
#      instance and one into tenant A of the multi-tenant instance, unless it is already there,
#   3. writes /bootstrap/env (KEY=value lines) for the test containers.
# Re-running keeps the same ids and timestamps (the first run's values are reused from /bootstrap/env).
set -eu

TEMPO_URL="${TEMPO_URL:-http://grafana-tempo:3200}"
TEMPO_OTLP_HTTP="${TEMPO_OTLP_HTTP:-http://grafana-tempo:4318}"
TEMPO_MT_URL="${TEMPO_MT_URL:-http://grafana-tempo-mt:3200}"
TEMPO_MT_OTLP_HTTP="${TEMPO_MT_OTLP_HTTP:-http://grafana-tempo-mt:4318}"
TENANT_A="${TENANT_A:-tenant-a}"
TENANT_B="${TENANT_B:-tenant-b}"
OUT=/bootstrap/env
SEED_TRACE_ID=5b8efff798038103d269b633813fc60c
SEED_TRACE_ID_MT=0af7651916cd43dd8448eb211c80319c
SEED_SERVICE=grow-sdk-seed-api
SEED_SERVICE_CHILD=grow-sdk-seed-db

log() { echo "bootstrap: $*"; }

wait_ready() { # url
  i=0
  until curl -fsS "$1/ready" 2>/dev/null | grep -q '^ready'; do
    i=$((i + 1))
    [ "$i" -gt 120 ] && { log "timeout waiting for $1/ready"; exit 1; }
    sleep 1
  done
}

wait_ready "$TEMPO_URL"
wait_ready "$TEMPO_MT_URL"
log "both Tempo instances ready"

# Reuse the first run's seed time so re-runs write the same env file.
SEED_START_S=""
if [ -f "$OUT" ]; then
  SEED_START_S="$(sed -n 's/^GROW_GRAFANA_TEMPO_SEED_START=//p' "$OUT")"
fi
[ -n "$SEED_START_S" ] || SEED_START_S="$(( $(date +%s) - 5 ))"

seed_payload() { # trace_id
  t0="${SEED_START_S}000000000"
  t1="$(( SEED_START_S ))250000000"   # +0.25 s
  t2="$(( SEED_START_S ))750000000"   # +0.75 s
  t3="$(( SEED_START_S + 1 ))000000000" # +1 s
  cat <<EOF
{"resourceSpans":[
 {"resource":{"attributes":[
   {"key":"service.name","value":{"stringValue":"$SEED_SERVICE"}},
   {"key":"service.version","value":{"stringValue":"1.2.3"}},
   {"key":"deployment.environment.name","value":{"stringValue":"test"}}]},
  "scopeSpans":[{"scope":{"name":"grow-sdk-bootstrap","version":"0.1.0"},"spans":[
   {"traceId":"$1","spanId":"1000000000000001","name":"GET /seed","kind":2,
    "startTimeUnixNano":"$t0","endTimeUnixNano":"$t3",
    "attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}},
                  {"key":"http.response.status_code","value":{"intValue":"200"}},
                  {"key":"grow.seed","value":{"boolValue":true}}],
    "events":[{"timeUnixNano":"$t1","name":"seed-event","attributes":[{"key":"n","value":{"intValue":"1"}}]}],
    "status":{"code":1}},
   {"traceId":"$1","spanId":"1000000000000002","parentSpanId":"1000000000000001","name":"call db","kind":3,
    "startTimeUnixNano":"$t1","endTimeUnixNano":"$t2",
    "links":[{"traceId":"$SEED_TRACE_ID_MT","spanId":"2000000000000001","attributes":[{"key":"link.kind","value":{"stringValue":"seed"}}]}],
    "status":{"code":0}}]}]},
 {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"$SEED_SERVICE_CHILD"}}]},
  "scopeSpans":[{"scope":{"name":"grow-sdk-bootstrap"},"spans":[
   {"traceId":"$1","spanId":"1000000000000003","parentSpanId":"1000000000000002","name":"SELECT seed","kind":2,
    "startTimeUnixNano":"$t1","endTimeUnixNano":"$t2",
    "attributes":[{"key":"db.system.name","value":{"stringValue":"postgresql"}}],
    "status":{"code":2,"message":"seed failure"}}]}]}
]}
EOF
}

has_trace() { # query_url trace_id [tenant]
  if [ -n "${3:-}" ]; then
    curl -fsS -H "X-Scope-OrgID: $3" "$1/api/v2/traces/$2" 2>/dev/null | grep -q '"resourceSpans"'
  else
    curl -fsS "$1/api/v2/traces/$2" 2>/dev/null | grep -q '"resourceSpans"'
  fi
}

seed() { # query_url otlp_url trace_id [tenant]
  if has_trace "$1" "$3" "${4:-}"; then
    log "seed trace $3 already present in $1 ${4:+(tenant $4)}"
    return 0
  fi
  if [ -n "${4:-}" ]; then
    seed_payload "$3" | curl -fsS -o /dev/null -X POST -H 'Content-Type: application/json' \
      -H "X-Scope-OrgID: $4" --data-binary @- "$2/v1/traces"
  else
    seed_payload "$3" | curl -fsS -o /dev/null -X POST -H 'Content-Type: application/json' \
      --data-binary @- "$2/v1/traces"
  fi
  i=0
  until has_trace "$1" "$3" "${4:-}"; do
    i=$((i + 1))
    [ "$i" -gt 60 ] && { log "seed trace $3 not queryable after 60 s"; exit 1; }
    sleep 1
  done
  log "seeded trace $3 into $1 ${4:+(tenant $4)}"
}

seed "$TEMPO_URL" "$TEMPO_OTLP_HTTP" "$SEED_TRACE_ID"
seed "$TEMPO_MT_URL" "$TEMPO_MT_OTLP_HTTP" "$SEED_TRACE_ID_MT" "$TENANT_A"

VERSION="$(curl -fsS "$TEMPO_URL/api/status/buildinfo" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"

TMP="$OUT.tmp"
cat > "$TMP" <<EOF
GROW_GRAFANA_TEMPO_URL=$TEMPO_URL
GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=$TEMPO_OTLP_HTTP
GROW_GRAFANA_TEMPO_OTLP_GRPC_ENDPOINT=http://grafana-tempo:4317
GROW_GRAFANA_TEMPO_MT_URL=$TEMPO_MT_URL
GROW_GRAFANA_TEMPO_MT_OTLP_ENDPOINT=$TEMPO_MT_OTLP_HTTP
GROW_GRAFANA_TEMPO_MT_OTLP_GRPC_ENDPOINT=http://grafana-tempo-mt:4317
GROW_GRAFANA_TEMPO_TENANT_ID=$TENANT_A
GROW_GRAFANA_TEMPO_OTHER_TENANT_ID=$TENANT_B
GROW_GRAFANA_TEMPO_SEED_TRACE_ID=$SEED_TRACE_ID
GROW_GRAFANA_TEMPO_MT_SEED_TRACE_ID=$SEED_TRACE_ID_MT
GROW_GRAFANA_TEMPO_SEED_SERVICE=$SEED_SERVICE
GROW_GRAFANA_TEMPO_SEED_CHILD_SERVICE=$SEED_SERVICE_CHILD
GROW_GRAFANA_TEMPO_SEED_START=$SEED_START_S
GROW_GRAFANA_TEMPO_SEED_END=$(( SEED_START_S + 1 ))
GROW_GRAFANA_TEMPO_SERVER_VERSION=$VERSION
EOF
chmod 0644 "$TMP"
mv "$TMP" "$OUT"
log "wrote $OUT ($(wc -l < "$OUT") keys, server version $VERSION)"
