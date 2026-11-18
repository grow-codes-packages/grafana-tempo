"""init_tracing / tempo_tracing_config / create_tempo_exporter (OTLP preset for Tempo)."""

from __future__ import annotations

import gzip
import tempfile
from pathlib import Path

import pytest
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
    OTLPSpanExporter as GrpcExporter,
)
from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
    OTLPSpanExporter as HttpExporter,
)
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

import grow_grafana_tempo as sdk
from _support import MockServer

P = "GROW_GRAFANA_TEMPO_"


def test_defaults() -> None:
    cfg = sdk.tempo_tracing_config(service_name="svc", env={})
    assert cfg.protocol == "http/protobuf"
    assert cfg.endpoint == sdk.DEFAULT_OTLP_HTTP_ENDPOINT
    assert dict(cfg.headers) == {}
    grpc = sdk.tempo_tracing_config(service_name="svc", protocol="grpc", env={})
    assert grpc.endpoint == sdk.DEFAULT_OTLP_GRPC_ENDPOINT


def test_endpoint_precedence() -> None:
    env = {"OTEL_EXPORTER_OTLP_ENDPOINT": "http://base:4318/"}
    assert (
        sdk.tempo_tracing_config(service_name="s", env=env).endpoint == "http://base:4318/v1/traces"
    )
    grpc = sdk.tempo_tracing_config(
        service_name="s", env={**env, "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc"}
    )
    assert grpc.endpoint == "http://base:4318/" and grpc.protocol == "grpc"
    env["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"] = "http://traces:4318/custom"
    assert (
        sdk.tempo_tracing_config(service_name="s", env=env).endpoint == "http://traces:4318/custom"
    )
    env[P + "OTLP_ENDPOINT"] = "http://grow:4318"
    assert sdk.tempo_tracing_config(service_name="s", env=env).endpoint == "http://grow:4318"
    explicit = sdk.tempo_tracing_config(service_name="s", endpoint="http://x:1", env=env)
    assert explicit.endpoint == "http://x:1"


def test_protocol_precedence_and_invalid() -> None:
    env = {"OTEL_EXPORTER_OTLP_PROTOCOL": "grpc", "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL": "native"}
    assert sdk.tempo_tracing_config(service_name="s", env=env).protocol == "native"
    env[P + "OTLP_PROTOCOL"] = "http/protobuf"
    assert sdk.tempo_tracing_config(service_name="s", env=env).protocol == "http/protobuf"
    with pytest.raises(sdk.ConfigError) as info:
        sdk.tempo_tracing_config(service_name="s", env={P + "OTLP_PROTOCOL": "carrier-pigeon"})
    assert P + "OTLP_PROTOCOL" in str(info.value) and "pigeon" not in str(info.value)


def test_headers_service_name_and_tenant() -> None:
    env = {
        "OTEL_EXPORTER_OTLP_HEADERS": "a=1",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS": "Authorization=Bearer%20tok, x-scope-orgid=old,",
        "OTEL_RESOURCE_ATTRIBUTES": "service.name=from-resource,team=x",
        "TEMPO_TENANT_ID": "tenant-env",
    }
    cfg = sdk.tempo_tracing_config(headers={"X-Extra": "1"}, env=env)
    assert dict(cfg.headers) == {
        "Authorization": "Bearer tok",
        "X-Extra": "1",
        "X-Scope-OrgID": "tenant-env",
    }
    assert cfg.service_name == "from-resource"
    assert "Bearer tok" not in repr(cfg)
    env["OTEL_SERVICE_NAME"] = "from-otel"
    cfg = sdk.tempo_tracing_config(tenant_id="tenant-kw", env=env)
    assert cfg.service_name == "from-otel" and cfg.headers["X-Scope-OrgID"] == "tenant-kw"
    with pytest.raises(sdk.ConfigError):
        sdk.tempo_tracing_config(service_name="s", tenant_id="bad/tenant", env={})
    with pytest.raises(sdk.ConfigError) as info:
        sdk.tempo_tracing_config(service_name="s", env={"OTEL_EXPORTER_OTLP_HEADERS": "novalue"})
    assert "OTEL_EXPORTER_OTLP_HEADERS" in str(info.value)
    assert sdk.parse_key_value_list(" k = v%2C1 ", "X") == {"k": "v,1"}


def test_config_object_counts_as_explicit() -> None:
    base = sdk.TracingConfig(service_name="obj", protocol="grpc", sampler="always_on")
    cfg = sdk.tempo_tracing_config(base, env={P + "OTLP_PROTOCOL": "http/protobuf"})
    assert cfg.protocol == "grpc" and cfg.endpoint == sdk.DEFAULT_OTLP_GRPC_ENDPOINT
    assert cfg.sampler == "always_on"
    cfg = sdk.tempo_tracing_config(base, env={}, service_name="kw")
    assert cfg.service_name == "kw"
    keep = sdk.tempo_tracing_config(base.with_changes(endpoint="http://e:4317"), env={})
    assert keep.endpoint == "http://e:4317"


def test_os_environ_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OTEL_SERVICE_NAME", "env-svc")
    monkeypatch.setenv(P + "TENANT_ID", "t-env")
    cfg = sdk.tempo_tracing_config()
    assert cfg.service_name == "env-svc" and cfg.headers["X-Scope-OrgID"] == "t-env"


def test_otlp_http_traces_url() -> None:
    assert sdk.otlp_http_traces_url("http://h:4318") == "http://h:4318/v1/traces"
    assert sdk.otlp_http_traces_url("http://h:4318/") == "http://h:4318/v1/traces"
    assert sdk.otlp_http_traces_url("http://h/otlp/v1/traces") == "http://h/otlp/v1/traces"


def test_create_exporters(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tempfile, "tempdir", str(tmp_path))  # PEM copies land in tmp_path
    http = sdk.create_tempo_exporter(sdk.TracingConfig(service_name="s"))
    assert isinstance(http, HttpExporter)
    http.shutdown()
    ca = tmp_path / "ca.pem"
    ca.write_text("not a real cert")
    for tls in (
        sdk.TlsConfig(ca_cert_pem="PEM DATA"),
        sdk.TlsConfig(insecure_skip_verify=True),
        sdk.TlsConfig(ca_cert_path=str(ca)),
    ):
        exporter = sdk.create_tempo_exporter(
            sdk.TracingConfig(service_name="s", tls=tls, compression="none")
        )
        assert isinstance(exporter, HttpExporter)
        exporter.shutdown()
    grpc = sdk.create_tempo_exporter(
        sdk.TracingConfig(service_name="s", protocol="grpc", headers={"X-Scope-OrgID": "t"})
    )
    assert isinstance(grpc, GrpcExporter)
    grpc.shutdown()
    secure = sdk.create_tempo_exporter(
        sdk.TracingConfig(
            service_name="s",
            protocol="grpc",
            endpoint="https://tempo:4317",
            compression="none",
            tls=sdk.TlsConfig(ca_cert_pem="-----BEGIN CERTIFICATE-----\n"),
        )
    )
    assert isinstance(secure, GrpcExporter)
    secure.shutdown()
    with pytest.raises(sdk.ConfigError):
        sdk.create_tempo_exporter(
            sdk.TracingConfig(
                service_name="s",
                protocol="grpc",
                endpoint="https://tempo:4317",
                tls=sdk.TlsConfig(insecure_skip_verify=True),
            )
        )
    with pytest.raises(sdk.ConfigError):
        sdk.create_tempo_exporter(
            sdk.TracingConfig(
                service_name="s",
                protocol="grpc",
                endpoint="https://tempo:4317",
                tls=sdk.TlsConfig(ca_cert_path=str(tmp_path / "missing.pem")),
            )
        )


def test_insecure_session_never_verifies() -> None:
    from grow_grafana_tempo.exporter import _insecure_session

    session = _insecure_session()
    settings = session.merge_environment_settings("https://x", {}, None, True, None)
    assert settings["verify"] is False
    session.close()


def test_init_tracing_exports_to_otlp_http(server: MockServer) -> None:
    handle = sdk.init_tracing(
        service_name="checkout",
        tenant_id="tenant-a",
        endpoint=server.url,
        set_global=False,
        env={},
    )
    try:
        with handle.tracer("t").start_as_current_span("work") as span:
            trace_id = format(span.get_span_context().trace_id, "032x")
        handle.force_flush(10)
    finally:
        handle.shutdown()
        handle.shutdown()
    posts = [s for s in server.seen if s.path == "/v1/traces"]
    assert posts, server.seen
    seen = posts[0]
    headers = {k.lower(): v for k, v in seen.headers.items()}
    assert headers["x-scope-orgid"] == "tenant-a"
    assert headers["content-type"] == "application/x-protobuf"
    body = gzip.decompress(seen.body) if headers.get("content-encoding") == "gzip" else seen.body
    request = ExportTraceServiceRequest.FromString(body)
    span_pb = request.resource_spans[0].scope_spans[0].spans[0]
    assert span_pb.trace_id.hex() == trace_id and span_pb.name == "work"


def test_init_tracing_requires_service_name() -> None:
    with pytest.raises(sdk.ConfigError):
        sdk.init_tracing(env={}, set_global=False)
