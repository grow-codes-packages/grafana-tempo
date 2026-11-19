// Fake Tempo for unit tests: serves the responses recorded against the real Tempo 3.1.1
// (test/fixtures/<function>.<case>.json, from the GRO-1379 prep run).

import { readFileSync } from "node:fs";
import {
  type MockResponse,
  type MockServer,
  type RecordedRequest,
  startServer,
} from "../helpers/server.js";

/** A recorded exchange. */
export interface Fixture {
  function: string;
  case: string;
  request: { method: string; path: string; query?: Record<string, string> };
  response: { status: number; contentType: string; body: unknown };
}

/** Loads `test/fixtures/<name>.json`. */
export function fixture(name: string): Fixture {
  return JSON.parse(
    readFileSync(new URL(`../fixtures/${name}.json`, import.meta.url), "utf8"),
  ) as Fixture;
}

/** The recorded response as a mock response. */
export function fx(name: string): MockResponse {
  const f = fixture(name);
  const body = f.response.body;
  return {
    status: f.response.status,
    headers: f.response.contentType === "" ? {} : { "content-type": f.response.contentType },
    body: typeof body === "string" ? body : JSON.stringify(body),
  };
}

/** Route value: a fixture name, a response, or a function of the request. */
export type Route = string | MockResponse | ((req: RecordedRequest) => MockResponse | string);

/** Path and decoded query of a recorded request. */
export function parse(req: RecordedRequest): { path: string; query: URLSearchParams } {
  const url = new URL(req.url, "http://x");
  return { path: url.pathname, query: url.searchParams };
}

/**
 * Starts a fake Tempo that answers by exact path (`/api/search`) or by path prefix ending in `*`
 * (`/api/v2/traces/*`). Unknown routes answer `404 page not found` like Tempo.
 */
export async function tempoServer(routes: Record<string, Route>): Promise<MockServer> {
  return startServer((req) => {
    const { path } = parse(req);
    const route =
      routes[path] ??
      Object.entries(routes).find(([k]) => k.endsWith("*") && path.startsWith(k.slice(0, -1)))?.[1];
    if (route === undefined) {
      return {
        status: 404,
        headers: { "content-type": "text/plain" },
        body: "404 page not found\n",
      };
    }
    const value = typeof route === "function" ? route(req) : route;
    return typeof value === "string" ? fx(value) : value;
  });
}

/** Default routes of a healthy single-tenant Tempo. */
export const TEMPO_ROUTES: Record<string, Route> = {
  "/ready": "ready.ok",
  "/api/echo": "echo.ok",
  "/api/status/buildinfo": "buildInfo.ok",
  "/status/version": "serverVersion.status-version-text",
  "/api/v2/search/tags": "searchTags.v2-scope-intrinsic",
};

export type { MockServer };
