// Runs the shared conformance scenarios (conformance/scenarios) against the real Tempo.
// Capabilities: core `version`; tracing `query` + `services`. Not declared: `dependencies`
// (Tempo has no dependency API; see docs/compatibility.md).

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { after, before, test } from "node:test";
import {
  TempoClient,
  TempoQueryClient,
  initTracing,
  pollUntil,
  withSpan,
} from "@grow-codes/grafana-tempo";
import { formatReport, runConformance } from "@grow-codes/grafana-tempo/testing";
import { loadBootstrapEnv, scenariosDir } from "./env.ts";

// One reporting service for every tracing scenario. Tempo lists a service only ~30 s after its
// first span, so `before` sends a warm-up span and waits until listServices shows it; the
// `tracing.services` scenario then checks the same service right after its own flush.
const SERVICE = `grow-ts-conformance-${randomBytes(4).toString("hex")}`;
let query: TempoQueryClient;

before(async () => {
  loadBootstrapEnv();
  query = TempoQueryClient.fromEnv();
  const warmup = initTracing({ serviceName: SERVICE, setGlobal: false });
  withSpan("warm-up", () => undefined, { tracer: warmup.tracer("conformance") });
  await warmup.forceFlush(30_000);
  await warmup.shutdown();
  await pollUntil(
    () => query.listServices(),
    (services) => services.includes(SERVICE),
    { timeoutMs: 150_000, intervalMs: 2000, operation: "conformance warm-up" },
  );
});

after(async () => {
  await query.close();
});

test("conformance: core", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core"],
    factories: { core: () => TempoClient.fromEnv() },
    capabilities: { core: ["version"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.ok(report.passed > 0);
});

test("conformance: tracing", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["tracing"],
    factories: {
      tracing: () => ({
        handle: initTracing({ serviceName: SERVICE, setGlobal: false }),
        query,
      }),
    },
    capabilities: { tracing: ["query", "services"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
  assert.ok(report.passed >= 2);
});
