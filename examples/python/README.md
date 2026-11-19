# Python examples

Install the package, point it at a running Grafana Tempo and run an example:

```bash
pip install grow-grafana-tempo
export GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200            # query API
export GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318  # OTLP/HTTP ingest
export GROW_GRAFANA_TEMPO_TENANT_ID=team-a                     # only for a multi-tenant Tempo
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_grafana_tempo` logger, retries and timeouts |
| `tracing_and_query.py` | `init_tracing` (OTLP), span helpers, `get_trace`, TraceQL search, tags, metrics |
