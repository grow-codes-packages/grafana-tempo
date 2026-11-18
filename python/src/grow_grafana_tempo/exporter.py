"""OpenTelemetry setup with the Grafana Tempo OTLP exporter preset (SPEC §5).

Tempo ingests OTLP only (gRPC ``4317``, HTTP ``4318``), so ``protocol="native"`` means OTLP/HTTP.
On a multi-tenant Tempo every export carries ``X-Scope-OrgID`` (HTTP header, gRPC metadata
``x-scope-orgid``).
"""

from __future__ import annotations

import dataclasses
import os
import tempfile
from collections.abc import Mapping, Sequence
from typing import Any
from urllib.parse import unquote, urlsplit

from opentelemetry.sdk.trace import SpanProcessor
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.sdk.trace.id_generator import IdGenerator

from ._core import ConfigError, EnvReader, TlsConfig
from ._tracing import PropagatorFactory, TracingConfig, TracingHandle, init_tracing_with
from .config import TENANT_HEADER, validate_tenant_id

__all__ = [
    "DEFAULT_OTLP_GRPC_ENDPOINT",
    "DEFAULT_OTLP_HTTP_ENDPOINT",
    "create_tempo_exporter",
    "init_tracing",
    "otlp_http_traces_url",
    "parse_key_value_list",
    "tempo_tracing_config",
]

#: Default OTLP/HTTP endpoint (Tempo's OTLP HTTP receiver).
DEFAULT_OTLP_HTTP_ENDPOINT = "http://127.0.0.1:4318"
#: Default OTLP/gRPC endpoint (Tempo's OTLP gRPC receiver).
DEFAULT_OTLP_GRPC_ENDPOINT = "http://127.0.0.1:4317"

_PROTOCOLS = ("grpc", "http/protobuf", "native")
_TENANT_VARS = ("TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID")


def parse_key_value_list(text: str, variable: str) -> dict[str, str]:
    """Parse ``key=value,key2=value2`` (values percent-decoded) as in
    ``OTEL_EXPORTER_OTLP_HEADERS``; :class:`ConfigError` naming ``variable`` when malformed."""
    out: dict[str, str] = {}
    for part in text.split(","):
        if not part.strip():
            continue
        key, sep, value = part.partition("=")
        if not sep or not key.strip():
            raise ConfigError(f"config: invalid {variable}: expected key=value pairs")
        out[key.strip()] = unquote(value.strip())
    return out


def _env_value(env: Mapping[str, str], name: str) -> tuple[str, str] | None:
    value = (env.get(name) or "").strip()
    return (name, value) if value else None


def _first_env(env: Mapping[str, str], names: Sequence[str]) -> tuple[str, str] | None:
    for name in names:
        hit = _env_value(env, name)
        if hit is not None:
            return hit
    return None


def _append_traces_path(endpoint: str) -> str:
    return endpoint.rstrip("/") + "/v1/traces"


def otlp_http_traces_url(endpoint: str) -> str:
    """The OTLP/HTTP traces URL: a bare ``scheme://host:port`` gets ``/v1/traces`` appended."""
    return _append_traces_path(endpoint) if urlsplit(endpoint).path in ("", "/") else endpoint


def tempo_tracing_config(
    config: TracingConfig | None = None,
    *,
    tenant_id: str | None = None,
    env: Mapping[str, str] | None = None,
    **fields: Any,
) -> TracingConfig:
    """Resolve the Tempo tracing settings from explicit values and the environment.

    Explicit values are ``config`` (a :class:`TracingConfig`, whose ``protocol`` counts as
    explicit) and/or keyword ``fields`` of :class:`TracingConfig` (they win over ``config``).
    Unset values come from the environment, per field:

    - ``endpoint``: ``GROW_GRAFANA_TEMPO_OTLP_ENDPOINT`` > ``OTEL_EXPORTER_OTLP_TRACES_ENDPOINT``
      (verbatim) > ``OTEL_EXPORTER_OTLP_ENDPOINT`` (+ ``/v1/traces`` for HTTP) > default
      (``http://127.0.0.1:4318``, or ``:4317`` for ``grpc``);
    - ``protocol``: ``GROW_GRAFANA_TEMPO_OTLP_PROTOCOL`` > ``OTEL_EXPORTER_OTLP_TRACES_PROTOCOL``
      > ``OTEL_EXPORTER_OTLP_PROTOCOL`` > ``http/protobuf``;
    - ``headers``: ``OTEL_EXPORTER_OTLP_TRACES_HEADERS`` > ``OTEL_EXPORTER_OTLP_HEADERS``, under
      the explicit headers;
    - ``service_name``: ``OTEL_SERVICE_NAME`` > ``service.name`` in ``OTEL_RESOURCE_ATTRIBUTES``;
    - tenant: ``tenant_id`` > ``GROW_GRAFANA_TEMPO_TENANT_ID`` > ``TEMPO_TENANT_ID`` >
      ``GROW_TEMPO_TENANT_ID``; it becomes the ``X-Scope-OrgID`` header.

    ``env`` defaults to ``os.environ``. Raises :class:`ConfigError` naming an invalid variable
    (never its value). The result is not validated (``init_tracing`` does that).
    """
    environ: Mapping[str, str] = os.environ if env is None else env
    reader = EnvReader(environ)
    explicit: dict[str, Any] = {}
    if config is not None:
        explicit = {f.name: getattr(config, f.name) for f in dataclasses.fields(config)}
        if explicit.get("endpoint") is None:
            explicit.pop("endpoint", None)
    explicit.update({k: v for k, v in fields.items() if v is not None})

    protocol = explicit.get("protocol")
    if protocol is None:
        found = reader.lookup("OTLP_PROTOCOL") or _first_env(
            environ, ("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL")
        )
        protocol = "http/protobuf"
        if found is not None:
            if found[1].strip() not in _PROTOCOLS:
                raise ConfigError(
                    f"config: invalid {found[0]}: expected grpc, http/protobuf or native"
                )
            protocol = found[1].strip()
    explicit["protocol"] = protocol

    if explicit.get("endpoint") is None:
        grow = reader.string("OTLP_ENDPOINT")
        traces = _env_value(environ, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        base = _env_value(environ, "OTEL_EXPORTER_OTLP_ENDPOINT")
        if grow is not None:
            endpoint = grow.strip()
        elif traces is not None:
            endpoint = traces[1]
        elif base is not None:
            endpoint = base[1] if protocol == "grpc" else _append_traces_path(base[1])
        else:
            endpoint = (
                DEFAULT_OTLP_GRPC_ENDPOINT if protocol == "grpc" else DEFAULT_OTLP_HTTP_ENDPOINT
            )
        explicit["endpoint"] = endpoint

    header_var = _first_env(
        environ, ("OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS")
    )
    headers = parse_key_value_list(header_var[1], header_var[0]) if header_var else {}
    headers.update(dict(explicit.get("headers") or {}))

    if not explicit.get("service_name"):
        resource_var = _env_value(environ, "OTEL_RESOURCE_ATTRIBUTES")
        resource = parse_key_value_list(resource_var[1], resource_var[0]) if resource_var else {}
        name = _env_value(environ, "OTEL_SERVICE_NAME")
        explicit["service_name"] = name[1] if name else resource.get("service.name", "")

    tenant = tenant_id if tenant_id is not None else reader.string("TENANT_ID", _TENANT_VARS)
    if tenant is not None:
        tenant = tenant.strip()
        validate_tenant_id(tenant)
        headers = {k: v for k, v in headers.items() if k.lower() != TENANT_HEADER.lower()}
        headers[TENANT_HEADER] = tenant
    explicit["headers"] = headers
    return TracingConfig(**explicit)


def _write_pem(pem: str) -> str:
    handle, path = tempfile.mkstemp(prefix="grow-tempo-ca-", suffix=".pem")
    with os.fdopen(handle, "w", encoding="utf-8") as f:
        f.write(pem)
    return path


def _read(path: str | None, what: str) -> bytes | None:
    if path is None:
        return None
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError:
        raise ConfigError(f"tracing: cannot read {what}") from None


def _http_exporter(config: TracingConfig, endpoint: str, tls: TlsConfig) -> SpanExporter:
    from opentelemetry.exporter.otlp.proto.http import Compression  # noqa: PLC0415
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (  # noqa: PLC0415
        OTLPSpanExporter,
    )

    ca = tls.ca_cert_path or (_write_pem(tls.ca_cert_pem) if tls.ca_cert_pem else None)
    session = _insecure_session() if tls.insecure_skip_verify else None
    return OTLPSpanExporter(
        endpoint=otlp_http_traces_url(endpoint),
        certificate_file=ca,
        client_key_file=tls.client_key_path,
        client_certificate_file=tls.client_cert_path,
        headers=dict(config.headers),
        timeout=config.batch.export_timeout_s,
        compression=Compression.Gzip if config.compression == "gzip" else Compression.NoCompression,
        session=session,
    )


def _grpc_exporter(config: TracingConfig, endpoint: str, tls: TlsConfig) -> SpanExporter:
    try:
        import grpc  # noqa: PLC0415 - optional extra
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (  # noqa: PLC0415
            OTLPSpanExporter,
        )
    except ImportError as exc:  # pragma: no cover - depends on the installed extras
        raise ConfigError(
            "tracing: protocol grpc needs the 'grpc' extra (grow-grafana-tempo[grpc])"
        ) from exc
    secure = urlsplit(endpoint).scheme == "https"
    credentials = None
    if secure:
        if tls.insecure_skip_verify:
            raise ConfigError("tracing: insecure_skip_verify is not supported for OTLP/gRPC")
        root = tls.ca_cert_pem.encode() if tls.ca_cert_pem else _read(tls.ca_cert_path, "CA")
        credentials = grpc.ssl_channel_credentials(
            root_certificates=root,
            private_key=_read(tls.client_key_path, "client key"),
            certificate_chain=_read(tls.client_cert_path, "client certificate"),
        )
    return OTLPSpanExporter(
        endpoint=endpoint,
        insecure=not secure,
        credentials=credentials,
        headers={k.lower(): v for k, v in config.headers.items()},
        timeout=config.batch.export_timeout_s,
        compression=grpc.Compression.Gzip
        if config.compression == "gzip"
        else grpc.Compression.NoCompression,
    )


def _insecure_session() -> Any:
    """A ``requests.Session`` that never verifies TLS (``insecure_skip_verify``, tests only)."""
    import requests  # noqa: PLC0415 - dependency of the OTLP/HTTP exporter

    class InsecureSession(requests.Session):
        def merge_environment_settings(self, *args: Any, **kwargs: Any) -> Any:
            settings = super().merge_environment_settings(*args, **kwargs)
            settings["verify"] = False
            return settings

    return InsecureSession()


def create_tempo_exporter(config: TracingConfig) -> SpanExporter:
    """The official OpenTelemetry OTLP exporter for a resolved config.

    ``grpc`` → OTLP/gRPC (``x-scope-orgid`` metadata; the ``grpc`` extra); ``http/protobuf`` and
    ``native`` → OTLP/HTTP protobuf to ``<endpoint>/v1/traces``. Applies headers, TLS,
    compression and the export timeout.
    """
    tls = config.tls or TlsConfig()
    if config.protocol == "grpc":
        return _grpc_exporter(config, config.endpoint or DEFAULT_OTLP_GRPC_ENDPOINT, tls)
    return _http_exporter(config, config.endpoint or DEFAULT_OTLP_HTTP_ENDPOINT, tls)


def init_tracing(
    config: TracingConfig | None = None,
    *,
    tenant_id: str | None = None,
    env: Mapping[str, str] | None = None,
    propagator_factories: Mapping[str, PropagatorFactory] | None = None,
    span_processors: Sequence[SpanProcessor] = (),
    id_generator: IdGenerator | None = None,
    **fields: Any,
) -> TracingHandle:
    """Set up OpenTelemetry tracing that exports to Grafana Tempo over OTLP.

    Resource, sampler, batch span processor, propagators and (unless ``set_global=False``)
    global registration come from the template :func:`init_tracing_with`; settings are resolved
    by :func:`tempo_tracing_config` (explicit > ``GROW_GRAFANA_TEMPO_OTLP_*`` > ``OTEL_*`` >
    default). Call ``shutdown()`` before the process exits.

    A multi-tenant Tempo answers an export without a tenant with HTTP 503 / gRPC
    ``Unavailable``, which the exporter retries until it drops the spans: set ``tenant_id``
    whenever Tempo runs with multi-tenancy.

    >>> handle = init_tracing(service_name="checkout", tenant_id="team-a")  # doctest: +SKIP
    >>> with_span("work", lambda span: do_work())  # doctest: +SKIP
    >>> handle.shutdown()  # doctest: +SKIP
    """
    resolved = tempo_tracing_config(config, tenant_id=tenant_id, env=env, **fields)
    return init_tracing_with(
        resolved,
        create_tempo_exporter,
        propagator_factories=propagator_factories,
        span_processors=span_processors,
        id_generator=id_generator,
    )
