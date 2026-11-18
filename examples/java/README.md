# Java examples

Install the package into your local Maven repository, point it at a running Grafana Tempo and run an
example:

```bash
make -C ../../java pack            # builds and installs codes.grow.packages:grafana-tempo
export GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200
export GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318
export GROW_GRAFANA_TEMPO_TENANT_ID=team-a   # only for a multi-tenant Tempo
mvn -q compile exec:java                                                  # BasicExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.tempo.examples.AsyncExample
```

Against the published package, set `-Dsdk.version=<version>` (or edit `pom.xml`) instead of
installing it locally.

| Class | Shows |
| - | - |
| `BasicExample` | health, ping, version; send a trace over OTLP, read it back by id, TraceQL search |
| `AsyncExample` | `…Async` methods, combining futures, cancellation |
| `HooksAndLoggingExample` | request hooks, a logger, retries and timeouts |
