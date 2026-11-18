# TypeScript integration tests

A consumer project: it installs the packed `@grow-codes/grafana-tempo` tarball (never the source tree)
and runs the integration and conformance tests with `node --test` against a real Grafana Tempo
(single-tenant and multi-tenant instances from `docker/compose.service.yml`).

```sh
cd typescript && make pack            # writes dist/grow-codes-grafana-tempo-<version>.tgz
cd integration && npm run install-package
GROW_BOOTSTRAP_ENV=/path/to/bootstrap/env npm test   # KEY=value file written by the bootstrap job
```

In CI and locally with Docker this runs inside `docker/Dockerfile.typescript`
(`make test-docker LANGS=typescript` from the repository root), which reads `/bootstrap/env`.
Search, tag and metrics tests poll for up to 150 s because Tempo indexes new spans after ~30 s.
