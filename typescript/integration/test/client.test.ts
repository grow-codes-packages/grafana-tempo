// Integration tests of TempoClient against the real Tempo from docker/compose.yml
// (single-tenant `grafana-tempo` and multi-tenant `grafana-tempo-mt`).

import assert from "node:assert/strict";
import { before, test } from "node:test";
import { TempoClient, TempoError } from "@grow-codes/grafana-tempo";
import { loadBootstrapEnv } from "./env.ts";

let mtUrl = "";

before(() => {
  loadBootstrapEnv();
  const urlVar = "GROW_GRAFANA_TEMPO_URL";
  assert.ok(process.env[urlVar], `${urlVar} must be set`);
  mtUrl = process.env.GROW_GRAFANA_TEMPO_MT_URL ?? "";
  assert.ok(mtUrl, "GROW_GRAFANA_TEMPO_MT_URL must be set (bootstrap)");
});

test("health reports a ready Tempo", async () => {
  const client = TempoClient.fromEnv();
  try {
    const health = await client.health();
    assert.deepEqual(health, { healthy: true, status: "ok", details: { ready: "ready" } });
    assert.equal(await client.ready(), true);
  } finally {
    await client.close();
  }
});

test("ping, echo, buildInfo and serverVersion round-trip", async () => {
  const client = TempoClient.fromEnv();
  try {
    const latency = await client.ping();
    assert.equal(typeof latency, "number");
    assert.ok(latency >= 0);
    assert.equal(await client.echo(), "echo");
    const info = await client.buildInfo();
    const expected = process.env.GROW_GRAFANA_TEMPO_SERVER_VERSION ?? "3.1.1";
    assert.equal(info.version, expected);
    assert.match(info.revision, /\S/);
    assert.match(info.goVersion, /^go/);
    assert.equal(await client.serverVersion(), expected);
  } finally {
    await client.close();
    await client.close();
  }
});

test("multi-tenant Tempo: ping needs X-Scope-OrgID (AUTHENTICATION without it)", async () => {
  const anonymous = new TempoClient({ url: mtUrl });
  const tenant = new TempoClient({
    url: mtUrl,
    tenantId: process.env.GROW_GRAFANA_TEMPO_TENANT_ID ?? "tenant-a",
  });
  try {
    await assert.rejects(anonymous.ping(), (err: unknown) => {
      assert.ok(err instanceof TempoError);
      assert.equal(err.code, "AUTHENTICATION");
      assert.equal(err.httpStatus, 401);
      assert.match(err.message, /X-Scope-OrgID/);
      return true;
    });
    // health/echo need no tenant, even on the multi-tenant instance
    assert.equal((await anonymous.health()).healthy, true);
    assert.equal(await anonymous.echo(), "echo");
    assert.ok((await tenant.ping()) >= 0);
  } finally {
    await anonymous.close();
    await tenant.close();
  }
});

test("an unreachable Tempo is reported, not thrown", async () => {
  const client = new TempoClient({ url: "http://127.0.0.1:9", retry: { maxRetries: 0 } });
  try {
    const health = await client.health();
    assert.equal(health.healthy, false);
    assert.equal(health.status, "unreachable");
  } finally {
    await client.close();
  }
});
