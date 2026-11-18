"""TraceQL builder: rendering, escaping, validation, structural operators."""

from __future__ import annotations

import datetime as dt

import pytest

import grow_grafana_tempo as sdk
from grow_grafana_tempo import TraceQL


def test_ticket_example() -> None:
    q = (
        TraceQL.span({"resource.service.name": "api"})
        .and_("duration", ">", "200ms")
        .select("span.http.status_code")
    )
    assert str(q) == (
        '{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)'
    )
    assert repr(q).startswith("TraceQL(")
    assert sdk.traceql_text(q) == str(q)
    assert sdk.traceql_text("{ }") == "{ }"


def test_empty_and_or() -> None:
    assert str(TraceQL.span()) == "{}"
    q = TraceQL.span().and_("status", "=", "error").or_("kind", "=", "server")
    assert str(q) == "{ status = error || kind = server }"


def test_escaping_and_quoted_names() -> None:
    q = TraceQL.span({"span.grow.note": 'a "quoted" \\ value ü\n\t\r'})
    assert str(q) == '{ span.grow.note = "a \\"quoted\\" \\\\ value ü\\n\\t\\r" }'
    assert TraceQL.field("span.grow weird.key") == 'span."grow weird.key"'
    assert TraceQL.field('span."already quoted"') == 'span."already quoted"'
    assert TraceQL.field(".http.method") == ".http.method"
    assert TraceQL.field("span.a-b") == 'span."a-b"'
    assert TraceQL.field("span.trailing.") == 'span."trailing."'
    assert TraceQL.quote('x"y') == '"x\\"y"'


def test_fields() -> None:
    assert TraceQL.field(" duration ") == "span:duration"
    assert TraceQL.field("trace:duration") == "trace:duration"
    assert TraceQL.field("rootServiceName") == "rootServiceName"
    assert TraceQL.field("event:name") == "event:name"
    for bad in ("", "service.name", "foo", "res.x", 'span."bad"quote"', 'span."trail\\"'):
        with pytest.raises(sdk.ValidationError):
            TraceQL.field(bad)


def test_values() -> None:
    assert TraceQL.value("span.x", True) == "true"
    assert TraceQL.value("span.x", False) == "false"
    assert TraceQL.value("span.x", 500) == "500"
    assert TraceQL.value("span.x", 1.5) == "1.5"
    assert TraceQL.value("span:duration", "1.5s") == "1.5s"
    assert TraceQL.value("span.x", "200ms") == '"200ms"'
    assert TraceQL.value("status", "ok") == "ok"
    assert TraceQL.value("status", "boom") == '"boom"'
    assert TraceQL.value("kind", "client") == "client"
    assert TraceQL.value("span:duration", dt.timedelta(milliseconds=200)) == "200ms"
    assert TraceQL.value("span:duration", dt.timedelta(hours=2)) == "2h"
    assert TraceQL.value("span:duration", dt.timedelta(minutes=3)) == "3m"
    assert TraceQL.value("span:duration", dt.timedelta(seconds=90)) == "90s"
    assert TraceQL.value("span:duration", dt.timedelta(microseconds=1500)) == "1500us"
    assert TraceQL.value("span:duration", dt.timedelta(0)) == "0s"
    with pytest.raises(sdk.ValidationError):
        TraceQL.value("span:duration", dt.timedelta(seconds=-1))
    with pytest.raises(sdk.ValidationError):
        TraceQL.value("span.x", float("nan"))
    with pytest.raises(sdk.ValidationError):
        TraceQL.value("span.x", [1])  # type: ignore[arg-type]


def test_operators() -> None:
    q = TraceQL.span().and_("span.http.response.status_code", ">=", 500)
    assert str(q) == "{ span.http.response.status_code >= 500 }"
    assert str(TraceQL.span().and_("name", "=~", "GET .*")) == '{ name =~ "GET .*" }'
    with pytest.raises(sdk.ValidationError):
        TraceQL.span().and_("name", "=~", 5)
    with pytest.raises(sdk.ValidationError):
        TraceQL.span().and_("name", "==", "x")


def test_structural_and_select() -> None:
    parent = TraceQL.span({"resource.service.name": "api"})
    child = TraceQL.span({"resource.service.name": "db"}).select("name")
    assert str(parent.descendant(child)) == (
        '{ resource.service.name = "api" } >> { resource.service.name = "db" }'
    )
    assert str(parent.child(child).and_("status", "=", "error")).endswith(
        '{ resource.service.name = "db" && status = error }'
    )
    assert " < " in str(parent.parent(child))
    assert " << " in str(parent.ancestor(child))
    assert " ~ " in str(parent.sibling(child))
    nested = parent.structural("&&", TraceQL.span().descendant(TraceQL.span()))
    assert str(nested) == '{ resource.service.name = "api" } && {} >> {}'
    assert str(parent.select("name", "status")).endswith("| select(name, status)")
    with pytest.raises(sdk.ValidationError):
        parent.structural("!!", child)
    with pytest.raises(sdk.ValidationError):
        parent.structural(">", "x")  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError):
        parent.select()


def test_immutable_eq_hash() -> None:
    base = TraceQL.span()
    extended = base.and_("name", "=", "x")
    assert str(base) == "{}"
    assert extended == TraceQL.span({"name": "x"})
    assert extended not in (base, "x")
    assert len({extended, TraceQL.span({"name": "x"})}) == 1


def test_check_traceql() -> None:
    assert sdk.check_traceql('  { name = "a}" } ', "op") == '{ name = "a}" }'
    assert sdk.check_traceql('{ name = "a\\"b" } | select(name)', "op")
    for bad in ("name = x", "{ (", "{ ) }", '{ name = "x }', "{ } }"):
        with pytest.raises(sdk.ValidationError):
            sdk.check_traceql(bad, "op")
