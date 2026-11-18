# TypeScript example

```sh
(cd ../../typescript && npm install && npm run build)
npm install
GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:<port> GROW_GRAFANA_TEMPO_TOKEN=... npm start
```

Replace the `file:` dependency in `package.json` with a version (`npm install @grow-codes/grafana-tempo`)
to run it against the published package.
