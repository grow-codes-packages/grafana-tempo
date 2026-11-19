"""OTLP/JSON → SPEC model conversion (recorded trace-by-id responses)."""

from __future__ import annotations

import base64

import grow_grafana_tempo as sdk
from _fixtures import load


def test_ids() -> None:
    assert sdk.otlp_id_to_hex("APdLwbBYoai4X5rgIx5AAQ==", 32) == "00f74bc1b058a1a8b85f9ae0231e4001"
    assert sdk.otlp_id_to_hex("oAAAAAAAAAE=", 16) == "a000000000000001"
    assert sdk.otlp_id_to_hex("A" * 16, 16) == "a" * 16
    assert sdk.otlp_id_to_hex(base64.b64encode(b"\x01").decode(), 16) == "0" * 15 + "1"
    for bad in (None, "", 5, "!!notbase64", base64.b64encode(bytes(8)).decode()):
        assert sdk.otlp_id_to_hex(bad, 16) is None
    assert sdk.otlp_id_to_hex(base64.b64encode(bytes(range(1, 20))).decode(), 32) is None


def test_nanos() -> None:
    assert sdk.nanos_to_micros("1795035005729668338") == 1795035005729668
    assert sdk.nanos_to_micros(2500) == 2
    assert sdk.nanos_to_micros(2500.0) == 2
    for empty in (None, "", True, "abc"):
        assert sdk.nanos_to_micros(empty) == 0


def test_any_values() -> None:
    kv = [
        {"key": "s", "value": {"stringValue": "x"}},
        {"key": "b", "value": {"boolValue": True}},
        {"key": "i", "value": {"intValue": "9007199254740993"}},
        {"key": "d", "value": {"doubleValue": 0.5}},
        {"key": "a", "value": {"arrayValue": {"values": [{"intValue": "1"}, {}]}}},
        {
            "key": "k",
            "value": {"kvlistValue": {"values": [{"key": "n", "value": {"stringValue": "y"}}]}},
        },
        {"key": "y", "value": {"bytesValue": "AAE="}},
        {"key": "empty", "value": {}},
        {"key": "badint", "value": {"intValue": "x"}},
        {"key": "baddouble", "value": {"doubleValue": "x"}},
        {"value": {"stringValue": "no key"}},
        "junk",
    ]
    assert sdk.from_key_values(kv) == {
        "s": "x",
        "b": True,
        "i": 9007199254740993,
        "d": 0.5,
        "a": [1],
        "k": {"n": "y"},
        "y": "AAE=",
    }
    assert sdk.from_any_value(None) is None
    assert sdk.from_key_values(None) == {}


def test_spans_from_v2_fixture() -> None:
    body = load("getTrace.v2-ok")["response"]["body"]
    spans = sdk.spans_from_resource_spans(body["trace"]["resourceSpans"])
    assert len(spans) == 4
    root = spans[0]
    assert root.trace_id == "00f74bc1b058a1a8b85f9ae0231e4001"
    assert root.parent_span_id is None
    assert root.name == "GET /orders" and root.kind == "server"
    assert root.service_name.endswith("-api")
    assert root.attributes["http.response.status_code"] == 200
    assert root.attributes["grow.note"] == 'a "quoted" \\ value ü'
    assert root.duration_micros == 450_000
    assert root.events and root.events[0].time_micros > root.start_micros
    assert [s.start_micros for s in spans] == sorted(s.start_micros for s in spans)
    services = {s.service_name for s in spans}
    assert len(services) == 2
    statuses = {s.status.code for s in spans}
    assert "ok" in statuses


def test_spans_edge_cases() -> None:
    tid = base64.b64encode(bytes(range(1, 17))).decode()
    sid = base64.b64encode(bytes(range(1, 9))).decode()
    resource_spans = [
        "junk",
        {
            "resource": {},
            "instrumentationLibrarySpans": [
                {
                    "spans": [
                        {
                            "traceId": tid,
                            "spanId": sid,
                            "kind": 2,
                            "status": {"code": 1},
                            "links": [
                                {"traceId": tid, "spanId": sid},
                                {"traceId": "", "spanId": sid},
                            ],
                        },
                        {"traceId": tid, "spanId": "", "name": "no id"},
                        {"traceId": tid, "spanId": sid, "kind": 99, "status": "bad"},
                        {"traceId": tid, "spanId": sid, "kind": "SPAN_KIND_WEIRD"},
                        {"traceId": tid, "spanId": sid, "kind": [], "status": {"code": 2}},
                        {"traceId": tid, "spanId": sid, "links": ["junk"]},
                        "junk",
                    ]
                },
                None,
            ],
        },
    ]
    spans = sdk.spans_from_resource_spans(resource_spans)
    assert len(spans) == 4
    first = next(s for s in spans if s.kind == "server")
    assert first.service_name == "unknown_service"
    assert first.status.code == "ok"
    assert len(first.links) == 1
    assert {s.kind for s in spans} == {"server", "unspecified"}
    assert any(s.status.code == "error" for s in spans)
    assert sdk.spans_from_resource_spans(None) == []
