# grow-grafana-tempo (Python)

Python SDK for [Grafana Tempo](https://grow-codes-packages.github.io/grafana-tempo/) by Grow Codes.
Unofficial and community maintained; "Grafana Tempo" is a trademark of Grafana Labs.

```bash
pip install grow-grafana-tempo
```

```python
from grow_grafana_tempo import TempoClient, TraceQL, init_tracing, with_span

# Send spans over OTLP (GROW_GRAFANA_TEMPO_OTLP_ENDPOINT / OTEL_EXPORTER_OTLP_*, default
# http://127.0.0.1:4318). tenant_id sets X-Scope-OrgID for a multi-tenant Tempo.
tracing = init_tracing(service_name="checkout", tenant_id="team-a")
trace_id = with_span("POST /checkout", lambda span: format(span.get_span_context().trace_id, "032x"))
tracing.shutdown()

# Query API: GROW_GRAFANA_TEMPO_URL / TEMPO_URL, GROW_GRAFANA_TEMPO_TENANT_ID / TEMPO_TENANT_ID.
with TempoClient.from_env() as client:
    print(client.health(), client.server_version())
    trace = client.query.wait_for_trace(trace_id)           # by id: visible at once
    query = TraceQL.span({"resource.service.name": "checkout"}).and_("duration", ">", "200ms")
    print(client.query.search(query, limit=20).traces)     # search/tags/metrics: ~30 s later
    print(client.query.search_tag_values("resource.service.name"))
    print(client.query.traceql_metrics(f"{query} | rate()", since_s=900).series)
```

The async client has the same methods:

```python
import asyncio
from grow_grafana_tempo import AsyncTempoClient

async def main() -> None:
    async with AsyncTempoClient.from_env() as client:
        print(await client.health())
        print(await client.query.list_services())

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_grafana_tempo`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_grafana_tempo.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/grafana-tempo/ ·
Source: https://github.com/grow-codes-packages/grafana-tempo · Licence: Apache-2.0
