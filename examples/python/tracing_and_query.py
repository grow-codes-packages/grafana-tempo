"""Send a trace to Tempo over OTLP, read it back by id, then search it with TraceQL.

Environment: GROW_GRAFANA_TEMPO_URL (query API, default http://127.0.0.1:3200),
GROW_GRAFANA_TEMPO_OTLP_ENDPOINT (OTLP/HTTP, default http://127.0.0.1:4318) and, for a
multi-tenant Tempo, GROW_GRAFANA_TEMPO_TENANT_ID.
"""

from __future__ import annotations

import time
import uuid

import grow_grafana_tempo as sdk
from grow_grafana_tempo import TraceQL


def main() -> None:
    service = f"example-{uuid.uuid4().hex[:8]}"
    handle = sdk.init_tracing(service_name=service, set_global=False)
    tracer = handle.tracer("example")

    def checkout(span: object) -> str:
        sdk.add_event("cart.loaded", {"items": 3})
        with tracer.start_as_current_span("SELECT cart"):
            time.sleep(0.25)
        print("log fields:", sdk.trace_fields())
        return sdk.current_trace_id() or ""

    trace_id = sdk.with_span(
        "POST /checkout", checkout, kind="server", attributes={"cart.items": 3}, tracer=tracer
    )
    handle.shutdown()  # flushes the batch processor

    with sdk.TempoClient.from_env() as client:
        trace = client.query.wait_for_trace(trace_id, timeout_s=30, min_spans=2)
        for node in trace.tree():
            print(node.span.name, [child.span.name for child in node.children])

        query = (
            TraceQL.span({"resource.service.name": service})
            .and_("duration", ">", "200ms")
            .select("name")
        )
        print("TraceQL:", query)
        print("search, tags and metrics see new data about 30 s after ingest …")
        result = sdk.poll_until(
            lambda: client.query.search(query).traces or None, timeout_s=90, interval_s=3
        )
        print(f"found {len(result)} trace(s); first {result[0].trace_id}")
        print("services:", client.query.list_services())
        print("operations:", client.query.list_operations(service))
        rate = client.query.traceql_metrics(
            f"{TraceQL.span({'resource.service.name': service})} | rate()", since_s=900
        )
        print("rate series:", len(rate.series))


if __name__ == "__main__":
    main()
