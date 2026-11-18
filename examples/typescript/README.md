# TypeScript example

Sends a trace to Grafana Tempo over OTLP, reads it back by id, then runs a TraceQL search, lists
the operations and counts the spans with a TraceQL metrics query.

```sh
(cd ../../typescript && npm install && npm run build)
npm install
GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200 \
GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318 npm start
```

Replace the `file:` dependency in `package.json` with a version (`npm install @grow-codes/grafana-tempo`)
to run it against the published package.
