import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { TempoClient } from "../../src/client.js";
import {
  type ConformanceOptions,
  conformanceCases,
  formatReport,
  runConformance,
} from "../../src/testing/index.js";
import type { MockServer } from "../helpers/server.js";
import { TEMPO_ROUTES, tempoServer } from "../support/tempo.js";

const scenariosDir = fileURLToPath(new URL("../../../conformance/scenarios/", import.meta.url));
let server: MockServer;

beforeAll(async () => {
  server = await tempoServer(TEMPO_ROUTES);
});

afterAll(async () => {
  await server.close();
});

function options(): ConformanceOptions {
  return {
    scenariosDir,
    categories: ["core"],
    factories: {
      core: () => new TempoClient({ url: server.url }),
    },
    capabilities: { core: ["version"] },
  };
}

describe("conformance (core) against recorded Tempo responses", () => {
  it("every core scenario passes", async () => {
    const report = await runConformance(options());
    expect(report.failed, formatReport(report)).toBe(0);
    expect(report.skipped).toBe(0);
    expect(report.results.map((r) => r.id)).toContain("core.health");
  });

  for (const c of conformanceCases(options())) {
    it.skipIf(c.skip !== undefined)(`case ${c.id}`, async () => {
      expect((await c.run()).status).toBe("passed");
    });
  }
});
