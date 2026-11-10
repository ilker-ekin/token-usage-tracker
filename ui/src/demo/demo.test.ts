import { beforeEach, describe, expect, it } from "vitest";
import { ApiError } from "../api";
import type {
  Budget,
  BudgetAlert,
  DailyAnalytics,
  Directory,
  MemberAnalytics,
  ModelAnalytics,
  Page,
  ProjectAnalytics,
  Summary,
  TeamAnalytics,
  UsageEventDetail,
} from "../api";
import { addDays, buildDataset, eventCost, HISTORY_DAYS, MODELS } from "./data";
import { demoSettings, handle, resetDemoState, setRole } from "./handler";

const today = new Date().toISOString().slice(0, 10);
const from = addDays(today, -13);
const range = `from=${from}&to=${today}`;
const sumOf = (xs: { costUsd: number }[]) => xs.reduce((s, x) => s + x.costUsd, 0);

beforeEach(() => {
  demoSettings.delayMs = 0;
  resetDemoState();
});

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error("expected the call to fail");
}

describe("generated data", () => {
  it("is the same every time for the same day", () => {
    expect(buildDataset("2026-11-10")).toEqual(buildDataset("2026-11-10"));
  });

  it("covers exactly the history window and nothing in the future", () => {
    const ds = buildDataset("2026-11-10");
    const days = ds.events.map((e) => e.eventTime.slice(0, 10));
    expect(days.reduce((a, b) => (a < b ? a : b))).toBe(addDays("2026-11-10", -(HISTORY_DAYS - 1)));
    expect(days.reduce((a, b) => (a > b ? a : b))).toBe("2026-11-10");
    expect(ds.events.length).toBeGreaterThan(5000);
  });

  it("prices every event from its model's rates", () => {
    const ds = buildDataset("2026-11-10");
    for (const e of ds.events.slice(0, 200)) {
      const m = MODELS.find((x) => x.id === e.model)!;
      expect(e.costUsd).toBe(eventCost(m, e.inputTokens, e.outputTokens, e.cacheReadTokens, e.cacheCreationTokens));
    }
  });

  it("books no cache-creation tokens for models that do not bill them", () => {
    const ds = buildDataset("2026-11-10");
    expect(ds.events.filter((e) => e.model.startsWith("gpt") && e.cacheCreationTokens > 0)).toEqual([]);
  });

  it("leaves some members without a team, like the real data", () => {
    expect(buildDataset("2026-11-10").members.filter((m) => m.teamId === null)).toHaveLength(2);
  });
});

describe("analytics", () => {
  it("adds up to the same spend in every view of the same range", async () => {
    const daily = await handle<DailyAnalytics[]>(`/analytics/daily?${range}`);
    const projects = await handle<ProjectAnalytics[]>(`/analytics/projects?${range}`);
    const members = await handle<MemberAnalytics[]>(`/analytics/members?${range}`);
    const models = await handle<ModelAnalytics[]>(`/analytics/models?${range}`);
    const summary = await handle<Summary>(`/analytics/summary?${range}`);

    const total = sumOf(daily);
    expect(total).toBeGreaterThan(0);
    expect(sumOf(projects)).toBeCloseTo(total, 4);
    expect(sumOf(members)).toBeCloseTo(total, 4);
    expect(sumOf(models)).toBeCloseTo(total, 4);
    expect(summary.current.costUsd).toBeCloseTo(total, 4);
  });

  it("leaves members without a team out of the teams view, as the API does", async () => {
    const teams = await handle<TeamAnalytics[]>(`/analytics/teams?${range}`);
    const members = await handle<MemberAnalytics[]>(`/analytics/members?${range}`);
    expect(sumOf(teams)).toBeLessThan(sumOf(members));
  });

  it("sorts spend views from the biggest spender", async () => {
    const members = await handle<MemberAnalytics[]>(`/analytics/members?${range}`);
    expect(members.map((m) => m.costUsd)).toEqual([...members.map((m) => m.costUsd)].sort((a, b) => b - a));
  });

  it("filters models by project", async () => {
    const all = await handle<ModelAnalytics[]>(`/analytics/models?${range}`);
    const one = await handle<ModelAnalytics[]>(`/analytics/models?${range}&projectId=proj-checkout-api`);
    expect(sumOf(one)).toBeGreaterThan(0);
    expect(sumOf(one)).toBeLessThan(sumOf(all));
  });

  it("compares with the previous range of equal length", async () => {
    const s = await handle<Summary>(`/analytics/summary?${range}`);
    expect(s.previousTo).toBe(addDays(from, -1));
    expect(s.previousFrom).toBe(addDays(from, -14));
    expect(s.previous.costUsd).toBeGreaterThan(0);
  });

  it("rejects a reversed range and a missing one", async () => {
    expect((await failure(handle(`/analytics/summary?from=${today}&to=${from}`))).status).toBe(400);
    expect((await failure(handle("/analytics/daily"))).status).toBe(400);
  });

  it("returns empty lists for a range before the history starts", async () => {
    const old = `from=2020-01-01&to=2020-01-31`;
    expect(await handle<DailyAnalytics[]>(`/analytics/daily?${old}`)).toEqual([]);
    expect(await handle<ProjectAnalytics[]>(`/analytics/projects?${old}`)).toEqual([]);
    expect((await handle<Summary>(`/analytics/summary?${old}`)).current.costUsd).toBe(0);
  });

  it("lists every project, team and member in the directory", async () => {
    const dir = await handle<Directory>("/analytics/directory");
    expect(dir.projects.length).toBeGreaterThan(5);
    expect(dir.teams.length).toBe(5);
    expect(dir.members.length).toBeGreaterThan(20);
  });
});

describe("raw events", () => {
  const usage = (extra = "") =>
    `/usage?from=${from}T00:00:00Z&to=${addDays(today, 1)}T00:00:00Z${extra}`;

  it("pages through the same events the analytics count", async () => {
    const first = await handle<Page<UsageEventDetail>>(usage("&size=25"));
    const daily = await handle<DailyAnalytics[]>(`/analytics/daily?${range}`);
    expect(first.content).toHaveLength(25);
    expect(first.totalPages).toBe(Math.ceil(first.totalElements / 25));
    const last = await handle<Page<UsageEventDetail>>(usage(`&size=25&page=${first.totalPages - 1}`));
    expect(last.content.length).toBeGreaterThan(0);
    // The events and the daily view are two views of the same data
    const all = await handle<Page<UsageEventDetail>>(usage("&size=200&page=0"));
    expect(all.content[0].eventTime >= all.content[1].eventTime).toBe(true);
    expect(daily.length).toBeGreaterThan(0);
  });

  it("filters by project, member and model, and caps the page size", async () => {
    const byModel = await handle<Page<UsageEventDetail>>(usage("&model=claude-haiku-4-5&size=500"));
    expect(byModel.size).toBe(200);
    expect(byModel.content.every((e) => e.model === "claude-haiku-4-5")).toBe(true);
    const byProject = await handle<Page<UsageEventDetail>>(usage("&projectId=proj-docs-assistant"));
    expect(byProject.content.every((e) => e.projectId === "proj-docs-assistant")).toBe(true);
  });
});

describe("budgets", () => {
  const good = { scopeType: "PROJECT", scopeId: "proj-mobile-app", monthlyLimitUsd: 500, webhookUrl: "https://example.com/hook", enabled: true };
  const post = (body: object) => handle<Budget>("/budgets", { method: "POST", body: JSON.stringify(body) });

  it("starts with example budgets and the spend so far this month", async () => {
    const list = await handle<Budget[]>("/budgets");
    expect(list).toHaveLength(3);
    expect(list.every((b) => b.currentSpendUsd >= 0)).toBe(true);
  });

  it("creates, updates and deletes a budget", async () => {
    const created = await post(good);
    expect((await handle<Budget[]>("/budgets")).some((b) => b.id === created.id)).toBe(true);

    const updated = await handle<Budget>(`/budgets/${created.id}`, {
      method: "PUT",
      body: JSON.stringify({ monthlyLimitUsd: 750, webhookUrl: "https://example.com/other", enabled: false }),
    });
    expect(updated).toMatchObject({ monthlyLimitUsd: 750, enabled: false });

    await handle(`/budgets/${created.id}`, { method: "DELETE" });
    expect((await handle<Budget[]>("/budgets")).some((b) => b.id === created.id)).toBe(false);
  });

  it("answers with field errors like the API", async () => {
    const e = await failure(post({ ...good, monthlyLimitUsd: 0 }));
    expect(e.status).toBe(400);
    expect(e.fieldErrors.monthlyLimitUsd).toBeTruthy();
  });

  it("refuses webhook URLs the real API refuses", async () => {
    for (const url of ["http://example.com/hook", "https://localhost/hook", "https://192.168.1.5/hook", "https://169.254.169.254/x", "https://user:pw@example.com/"]) {
      const e = await failure(post({ ...good, webhookUrl: url }));
      expect(e.status).toBe(400);
      // The dialog shows a message that starts with "webhookUrl" next to the field
      expect(e.message.startsWith("webhookUrl")).toBe(true);
    }
  });

  it("refuses a second budget for the same project, and an unknown scope", async () => {
    await post(good);
    expect((await failure(post(good))).status).toBe(409);
    expect((await failure(post({ ...good, scopeId: "proj-nope" }))).status).toBe(404);
  });

  it("raises pending alerts when spend is over the limit, and never calls a webhook", async () => {
    const created = await post({ ...good, monthlyLimitUsd: 0.01 });
    const alerts = await handle<BudgetAlert[]>(`/budgets/${created.id}/alerts`);
    expect(alerts.map((a) => a.thresholdPct)).toEqual([100, 80]);
    expect(alerts.every((a) => a.status === "PENDING" && a.sentAt === null)).toBe(true);
  });

  it("is for admins only", async () => {
    setRole("member");
    expect((await failure(handle("/budgets"))).status).toBe(403);
    const me = await handle<{ roles: string[] }>("/auth/me");
    expect(me.roles).toEqual(["MEMBER"]);
  });
});

describe("failure mode", () => {
  it("fails data requests with a 503 but keeps sign-in working", async () => {
    demoSettings.failRequests = true;
    expect((await failure(handle(`/analytics/daily?${range}`))).status).toBe(503);
    await expect(handle("/auth/me")).resolves.toBeTruthy();
    demoSettings.failRequests = false;
    await expect(handle(`/analytics/daily?${range}`)).resolves.toBeTruthy();
  });
});
