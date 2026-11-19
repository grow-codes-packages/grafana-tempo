"""Conversion of Tempo's OTLP/JSON trace responses into the SPEC §5 model.

Tempo serialises traces as protobuf JSON: ids are base64 bytes, timestamps decimal strings in
nanoseconds, enums names (``SPAN_KIND_SERVER``, ``STATUS_CODE_ERROR``). The shapes are the
OpenTelemetry proto (Apache-2.0); no Tempo code is used.
"""

from __future__ import annotations

import base64
import binascii
import re
from collections.abc import Iterable, Mapping
from typing import Any

from ._core import ValidationError
from ._tracing import (
    Span,
    SpanEvent,
    SpanKindName,
    SpanLink,
    SpanStatus,
    StatusCodeName,
    sort_spans,
)

__all__ = [
    "from_any_value",
    "from_key_values",
    "nanos_to_micros",
    "otlp_id_to_hex",
    "spans_from_resource_spans",
]

_HEX = re.compile(r"^[0-9a-fA-F]+$")
_KINDS: tuple[SpanKindName, ...] = (
    "unspecified",
    "internal",
    "server",
    "client",
    "producer",
    "consumer",
)


def otlp_id_to_hex(value: Any, hex_length: int) -> str | None:
    """Convert an id from trace-by-id JSON (base64 bytes) to lower-case hex of ``hex_length``.

    Ids that already are hex of that length are kept. Returns ``None`` for a missing, empty,
    undecodable or all-zero id.
    """
    if not isinstance(value, str) or not value:
        return None
    if len(value) == hex_length and _HEX.match(value):
        text = value.lower()
    else:
        try:
            text = base64.b64decode(value, validate=True).hex()
        except (binascii.Error, ValueError):
            return None
    if not text or len(text) > hex_length or set(text) == {"0"}:
        return None
    return text.rjust(hex_length, "0")


def nanos_to_micros(value: Any) -> int:
    """Integer microseconds from nanoseconds given as a decimal string or number (0 if unset)."""
    if isinstance(value, bool) or value is None or value == "":
        return 0
    if isinstance(value, int):
        return value // 1000
    if isinstance(value, float):
        return int(value // 1000)
    try:
        return int(str(value).strip()) // 1000
    except ValueError:
        return 0


def from_any_value(value: Any) -> Any:
    """Convert an OTLP ``AnyValue`` to a plain value (``None`` for an empty value).

    ``intValue`` strings become ``int``, ``doubleValue`` ``float``, arrays lists, key/value lists
    dicts; ``bytesValue`` stays base64 text.
    """
    if not isinstance(value, Mapping):
        return None
    if "stringValue" in value:
        return value["stringValue"]
    if "boolValue" in value:
        return bool(value["boolValue"])
    if "intValue" in value:
        try:
            return int(value["intValue"])
        except (TypeError, ValueError):
            return None
    if "doubleValue" in value:
        try:
            return float(value["doubleValue"])
        except (TypeError, ValueError):
            return None
    if "arrayValue" in value:
        items = (value["arrayValue"] or {}).get("values") or []
        return [v for v in (from_any_value(item) for item in items) if v is not None]
    if "kvlistValue" in value:
        return from_key_values((value["kvlistValue"] or {}).get("values"))
    if "bytesValue" in value:
        return value["bytesValue"]
    return None


def from_key_values(items: Iterable[Any] | None) -> dict[str, Any]:
    """Convert an OTLP ``KeyValue`` list to a dict (entries with empty values are dropped)."""
    out: dict[str, Any] = {}
    for item in items or []:
        if not isinstance(item, Mapping) or not isinstance(item.get("key"), str):
            continue
        converted = from_any_value(item.get("value"))
        if converted is not None:
            out[item["key"]] = converted
    return out


def _kind(value: Any) -> SpanKindName:
    if isinstance(value, int) and not isinstance(value, bool):
        return _KINDS[value] if 0 <= value < len(_KINDS) else "unspecified"
    if not isinstance(value, str):
        return "unspecified"
    word = value.removeprefix("SPAN_KIND_").lower()
    for kind in _KINDS:
        if kind == word:
            return kind
    return "unspecified"


def _status(value: Any) -> SpanStatus:
    if not isinstance(value, Mapping):
        return SpanStatus()
    code = value.get("code")
    name: StatusCodeName = "unset"
    if code in (1, "STATUS_CODE_OK"):
        name = "ok"
    elif code in (2, "STATUS_CODE_ERROR"):
        name = "error"
    message = value.get("message")
    return SpanStatus(name, message if isinstance(message, str) and message else None)


def _links(items: Any) -> list[SpanLink]:
    out: list[SpanLink] = []
    for link in items or []:
        trace_id = otlp_id_to_hex(link.get("traceId"), 32)
        span_id = otlp_id_to_hex(link.get("spanId"), 16)
        if trace_id is not None and span_id is not None:
            out.append(SpanLink(trace_id, span_id, from_key_values(link.get("attributes"))))
    return out


def _span(raw: Mapping[str, Any], service_name: str) -> Span | None:
    trace_id = otlp_id_to_hex(raw.get("traceId"), 32)
    span_id = otlp_id_to_hex(raw.get("spanId"), 16)
    if trace_id is None or span_id is None:
        return None
    start = nanos_to_micros(raw.get("startTimeUnixNano"))
    end = nanos_to_micros(raw.get("endTimeUnixNano"))
    try:
        return Span(
            trace_id=trace_id,
            span_id=span_id,
            parent_span_id=otlp_id_to_hex(raw.get("parentSpanId"), 16),
            name=str(raw.get("name") or ""),
            kind=_kind(raw.get("kind")),
            service_name=service_name,
            start_micros=start,
            end_micros=end,
            duration_micros=max(0, end - start),
            status=_status(raw.get("status")),
            attributes=from_key_values(raw.get("attributes")),
            events=[
                SpanEvent(
                    str(e.get("name") or ""),
                    nanos_to_micros(e.get("timeUnixNano")),
                    from_key_values(e.get("attributes")),
                )
                for e in raw.get("events") or []
            ],
            links=_links(raw.get("links")),
        )
    except (ValidationError, AttributeError, TypeError):
        return None


def spans_from_resource_spans(resource_spans: Iterable[Any] | None) -> list[Span]:
    """Flatten OTLP ``resourceSpans`` → ``scopeSpans`` → spans into model spans.

    Ids base64 → hex, nanoseconds → microseconds, enums → names, ``service_name`` from the
    resource ``service.name`` (``"unknown_service"`` when absent). Spans with unusable ids are
    skipped. Sorted by start time, then span id.
    """
    spans: list[Span] = []
    for rs in resource_spans or []:
        if not isinstance(rs, Mapping):
            continue
        resource = from_key_values((rs.get("resource") or {}).get("attributes"))
        service = resource.get("service.name")
        service_name = service if isinstance(service, str) and service else "unknown_service"
        scopes = [*(rs.get("scopeSpans") or []), *(rs.get("instrumentationLibrarySpans") or [])]
        for scope in scopes:
            for raw in (scope or {}).get("spans") or []:
                if isinstance(raw, Mapping):
                    span = _span(raw, service_name)
                    if span is not None:
                        spans.append(span)
    return sort_spans(spans)
