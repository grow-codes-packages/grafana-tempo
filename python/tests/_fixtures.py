"""Recorded Tempo 3.1.1 responses (tests/fixtures) served through ``httpx.MockTransport``."""

from __future__ import annotations

import json
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

import httpx

import grow_grafana_tempo as sdk
from _support import make_config

FIXTURES = Path(__file__).parent / "fixtures"
BASE = "http://tempo.test"


def load(name: str) -> dict[str, Any]:
    """The fixture document ``tests/fixtures/<name>.json``."""
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))  # type: ignore[no-any-return]


def response(status: int, body: Any = None, content_type: str | None = None) -> httpx.Response:
    """A response with a JSON body (dict/list) or a text body (str)."""
    if isinstance(body, (dict, list)):
        return httpx.Response(
            status,
            content=json.dumps(body).encode(),
            headers={"content-type": content_type or "application/json"},
        )
    text = "" if body is None else str(body)
    return httpx.Response(
        status, content=text.encode(), headers={"content-type": content_type or "text/plain"}
    )


def fixture_response(name: str) -> httpx.Response:
    resp = load(name)["response"]
    return response(resp["status"], resp["body"], resp.get("contentType") or "")


def raw_path(request: httpx.Request) -> str:
    """The request path as sent (percent-encoding kept, no query)."""
    return request.url.raw_path.decode().split("?", 1)[0]


class Router:
    """Answers queued responses per path (last one repeats); records every request."""

    def __init__(self) -> None:
        self.routes: dict[str, deque[httpx.Response]] = defaultdict(deque)
        self.requests: list[httpx.Request] = []

    def add(self, path: str, *responses: httpx.Response) -> Router:
        self.routes[path].extend(responses)
        return self

    def fixture(self, name: str, path: str | None = None) -> Router:
        """Queue a fixture's response at its recorded path (or ``path``)."""
        return self.add(path or load(name)["request"]["path"], fixture_response(name))

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        queue = self.routes.get(raw_path(request))
        if not queue:
            return response(404, "404 page not found\n")
        return queue.popleft() if len(queue) > 1 else queue[0]

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self)

    def last(self, path: str | None = None) -> httpx.Request:
        matching = [r for r in self.requests if path is None or raw_path(r) == path]
        return matching[-1]

    def params(self, path: str | None = None) -> dict[str, str]:
        return dict(self.last(path).url.params)


def sync_client(router: Router, **kw: Any) -> sdk.TempoClient:
    return sdk.TempoClient(make_config(BASE, **kw), http_transport=router.transport())


def async_client(router: Router, **kw: Any) -> sdk.AsyncTempoClient:
    return sdk.AsyncTempoClient(make_config(BASE, **kw), http_transport=router.transport())
