"""TraceQL query builder with escaping (Grafana Tempo).

>>> str(TraceQL.span({"resource.service.name": "api"})
...     .and_("duration", ">", "200ms")
...     .select("span.http.status_code"))
'{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)'
"""

from __future__ import annotations

import datetime as _dt
import math
import re
from collections.abc import Mapping
from typing import Literal, TypeAlias

from ._core import ValidationError

__all__ = [
    "SPANSET_OPERATORS",
    "TRACEQL_OPERATORS",
    "TRACEQL_SCOPES",
    "TraceQL",
    "TraceQLOperator",
    "TraceQLValue",
    "check_traceql",
    "traceql_text",
]

TraceQLOperator = Literal["=", "!=", ">", ">=", "<", "<=", "=~", "!~"]
#: A condition value: strings are quoted and escaped (except duration literals on duration
#: fields, ``ok|error|unset`` on ``status`` and span kinds on ``kind``); ``timedelta`` becomes a
#: duration literal (``200ms``); numbers and booleans are literals.
TraceQLValue: TypeAlias = str | int | float | bool | _dt.timedelta

#: Comparison operators accepted by :meth:`TraceQL.and_` / :meth:`TraceQL.or_`.
TRACEQL_OPERATORS: tuple[str, ...] = ("=", "!=", ">", ">=", "<", "<=", "=~", "!~")
#: Span-set operators accepted by :meth:`TraceQL.structural`.
SPANSET_OPERATORS: tuple[str, ...] = (">", ">>", "<", "<<", "~", "&&", "||")
#: Attribute scopes (``resource.``, ``span.``, …); a leading ``.`` means "span or resource".
TRACEQL_SCOPES: tuple[str, ...] = ("resource", "span", "event", "link", "instrumentation")

_BARE_INTRINSICS = frozenset(
    {
        "name",
        "status",
        "statusMessage",
        "kind",
        "duration",
        "rootName",
        "rootServiceName",
        "traceDuration",
        "childCount",
        "nestedSetLeft",
        "nestedSetRight",
        "nestedSetParent",
    }
)
_SCOPED_INTRINSIC = re.compile(r"^(span|trace|event|link|instrumentation):[A-Za-z][A-Za-z0-9_]*$")
_SCOPED_ATTRIBUTE = re.compile(r"^(resource|span|event|link|instrumentation)?\.(.+)$", re.S)
_PLAIN_ATTRIBUTE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.]*$")
_DURATION_LITERAL = re.compile(r"^\d+(\.\d+)?(ns|us|µs|ms|s|m|h)$")
_DURATION_FIELDS = frozenset({"span:duration", "trace:duration", "traceDuration"})
_STATUS_FIELDS = frozenset({"status", "span:status"})
_STATUS_WORDS = frozenset({"ok", "error", "unset"})
_KIND_FIELDS = frozenset({"kind", "span:kind"})
_KIND_WORDS = frozenset({"unspecified", "internal", "server", "client", "producer", "consumer"})
_ESCAPES = {"\\": "\\\\", '"': '\\"', "\n": "\\n", "\t": "\\t", "\r": "\\r"}


def _quote(text: str) -> str:
    return '"' + "".join(_ESCAPES.get(ch, ch) for ch in text) + '"'


def _duration_literal(value: _dt.timedelta) -> str:
    micros = (value.days * 86_400 + value.seconds) * 1_000_000 + value.microseconds
    if micros < 0:
        raise ValidationError("traceql: durations must not be negative")
    if micros == 0:
        return "0s"
    for unit, size in (("h", 3_600_000_000), ("m", 60_000_000), ("s", 1_000_000), ("ms", 1000)):
        if micros % size == 0:
            return f"{micros // size}{unit}"
    return f"{micros}us"


def _attribute_name(rest: str, field: str) -> str:
    if len(rest) >= 2 and rest.startswith('"') and rest.endswith('"'):
        inner = rest[1:-1]
        if re.search(r'(^|[^\\])"', inner) or inner.endswith("\\"):
            raise ValidationError(f"traceql: invalid quoted attribute name in {field!r}")
        return rest
    if _PLAIN_ATTRIBUTE.match(rest) and not rest.endswith("."):
        return rest
    return _quote(rest)


# One span-set filter: the operator joining it to the previous filter (empty for the first)
# and its conditions as (join, text) pairs.
_Part = tuple[str, tuple[tuple[str, str], ...]]


class TraceQL:
    """Immutable TraceQL builder: span-set filters, structural operators and ``select()``.

    Field names are validated (unknown scopes or intrinsics raise :class:`ValidationError`,
    because some Tempo endpoints silently ignore an invalid query); attribute names with spaces
    or special characters are quoted after the scope (``span."grow weird.key"``); the bare
    intrinsic ``duration`` is written as ``span:duration``. Every method returns a new builder.
    ``str(query)`` renders the TraceQL text.
    """

    __slots__ = ("_parts", "_selects")

    def __init__(self) -> None:
        self._parts: tuple[_Part, ...] = (("", ()),)
        self._selects: tuple[str, ...] = ()

    def _copy(self, parts: tuple[_Part, ...], selects: tuple[str, ...]) -> TraceQL:
        out = TraceQL()
        out._parts = parts
        out._selects = selects
        return out

    @classmethod
    def span(cls, filters: Mapping[str, TraceQLValue] | None = None) -> TraceQL:
        """Start a span-set filter; each ``filters`` entry becomes ``<field> = <value>`` joined
        with ``&&`` in insertion order. ``TraceQL.span()`` alone matches every span (``{}``)."""
        query = cls()
        for field, value in (filters or {}).items():
            query = query.and_(field, "=", value)
        return query

    @staticmethod
    def field(name: str) -> str:
        """Render a field name: validate the scope or intrinsic and quote odd attribute names."""
        field = name.strip()
        if not field:
            raise ValidationError("traceql: field name must not be empty")
        if field == "duration":
            return "span:duration"
        if field in _BARE_INTRINSICS or _SCOPED_INTRINSIC.match(field):
            return field
        match = _SCOPED_ATTRIBUTE.match(field)
        if match is not None:
            return f"{match.group(1) or ''}.{_attribute_name(match.group(2), field)}"
        raise ValidationError(
            f"traceql: unknown scope or intrinsic in {field!r} (use resource., span., event., "
            "link., instrumentation., . or an intrinsic)"
        )

    @staticmethod
    def quote(text: str) -> str:
        """Quote ``text`` as a TraceQL string literal (``\\``, ``"`` and control characters
        escaped; Unicode passes through)."""
        return _quote(text)

    @staticmethod
    def value(field: str, value: TraceQLValue) -> str:
        """Render ``value`` for ``field`` (a name already rendered by :meth:`field`)."""
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, int):
            return str(value)
        if isinstance(value, float):
            if not math.isfinite(value):
                raise ValidationError("traceql: numeric values must be finite")
            return repr(value)
        if isinstance(value, _dt.timedelta):
            return _duration_literal(value)
        if not isinstance(value, str):
            raise ValidationError(
                "traceql: values must be strings, numbers, booleans or timedeltas"
            )
        if field in _DURATION_FIELDS and _DURATION_LITERAL.match(value):
            return value
        if field in _STATUS_FIELDS and value in _STATUS_WORDS:
            return value
        if field in _KIND_FIELDS and value in _KIND_WORDS:
            return value
        return _quote(value)

    def _add(self, join: str, field: str, op: str, value: TraceQLValue) -> TraceQL:
        if op not in TRACEQL_OPERATORS:
            raise ValidationError(f"traceql: unsupported operator {op!r}")
        name = TraceQL.field(field)
        if op in ("=~", "!~"):
            if not isinstance(value, str):
                raise ValidationError(f"traceql: operator {op} needs a string value")
            rendered = _quote(value)
        else:
            rendered = TraceQL.value(name, value)
        prefix, conditions = self._parts[-1]
        last = (prefix, (*conditions, (join, f"{name} {op} {rendered}")))
        return self._copy((*self._parts[:-1], last), self._selects)

    def and_(self, field: str, op: TraceQLOperator | str, value: TraceQLValue) -> TraceQL:
        """Add ``&& <field> <op> <value>`` to the last span-set filter."""
        return self._add("&&", field, op, value)

    def or_(self, field: str, op: TraceQLOperator | str, value: TraceQLValue) -> TraceQL:
        """Add ``|| <field> <op> <value>`` to the last span-set filter."""
        return self._add("||", field, op, value)

    def structural(self, op: str, other: TraceQL) -> TraceQL:
        """Join ``other``'s span-set filters with a span-set operator: ``>`` (child), ``>>``
        (descendant), ``<`` (parent), ``<<`` (ancestor), ``~`` (sibling), ``&&`` / ``||``
        (both / either). ``other``'s ``select()`` is ignored."""
        if op not in SPANSET_OPERATORS:
            raise ValidationError(f"traceql: unknown span-set operator {op!r}")
        if not isinstance(other, TraceQL):
            raise ValidationError("traceql: the span-set operand must be a TraceQL builder")
        joined = tuple(
            (op if index == 0 else prefix, conditions)
            for index, (prefix, conditions) in enumerate(other._parts)
        )
        return self._copy(self._parts + joined, self._selects)

    def child(self, other: TraceQL) -> TraceQL:
        """``self > other``: spans of ``other`` that are direct children."""
        return self.structural(">", other)

    def descendant(self, other: TraceQL) -> TraceQL:
        """``self >> other``: spans of ``other`` below ``self`` at any depth."""
        return self.structural(">>", other)

    def parent(self, other: TraceQL) -> TraceQL:
        """``self < other``: spans of ``other`` that are direct parents."""
        return self.structural("<", other)

    def ancestor(self, other: TraceQL) -> TraceQL:
        """``self << other``: spans of ``other`` above ``self`` at any depth."""
        return self.structural("<<", other)

    def sibling(self, other: TraceQL) -> TraceQL:
        """``self ~ other``: spans of ``other`` with the same parent."""
        return self.structural("~", other)

    def select(self, *fields: str) -> TraceQL:
        """Add fields to ``| select(…)`` so search returns them on every matched span."""
        if not fields:
            raise ValidationError("traceql: select() needs a field")
        return self._copy(self._parts, self._selects + tuple(TraceQL.field(f) for f in fields))

    def __str__(self) -> str:
        out = []
        for prefix, conditions in self._parts:
            body = ""
            for index, (join, text) in enumerate(conditions):
                body += text if index == 0 else f" {join} {text}"
            spanset = f"{{ {body} }}" if body else "{}"
            out.append(f"{prefix} {spanset}" if prefix else spanset)
        text = " ".join(out)
        if self._selects:
            text += f" | select({', '.join(self._selects)})"
        return text

    def __repr__(self) -> str:
        return f"TraceQL({str(self)!r})"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, TraceQL) and str(other) == str(self)

    def __hash__(self) -> int:
        return hash(str(self))


def traceql_text(query: str | TraceQL) -> str:
    """Render a query argument (text or :class:`TraceQL`) to text."""
    return query if isinstance(query, str) else str(query)


def check_traceql(query: str, operation: str) -> str:
    """Light client-side check of a TraceQL text; returns it trimmed.

    The query must start with ``{`` and quotes, braces and parentheses must balance. Tempo's tag
    endpoints ignore an invalid ``q`` and return unfiltered values, so the SDK refuses obviously
    broken queries there (:class:`ValidationError`).
    """
    text = query.strip()
    if not text.startswith("{"):
        raise ValidationError(f'{operation}: TraceQL query must start with "{{"')
    stack: list[str] = []
    in_string = False
    escaped = False
    for ch in text:
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in "{(":
            stack.append("}" if ch == "{" else ")")
        elif ch in "})" and (not stack or stack.pop() != ch):
            raise ValidationError(f"{operation}: unbalanced TraceQL query")
    if in_string or stack:
        raise ValidationError(f"{operation}: unbalanced TraceQL query")
    return text
