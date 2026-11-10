// In-browser stand-in for the API, used only by the demo build (VITE_DEMO=true).
// It answers the same paths with the same shapes and errors, from the generated
// dataset. Nothing here touches the network. Budgets are kept in this browser's
// localStorage (memory if that is blocked) and webhooks are never called.

import { ApiError } from "../api";
import type {
  Budget,
  BudgetAlert,
  DailyAnalytics,
  Directory,
  MemberAnalytics,
  Me,
  ModelAnalytics,
  Page,
  ProjectAnalytics,
  Summary,
  TeamAnalytics,
  Totals,
  UsageEventDetail,
} from "../api";
import { addDays, buildDataset, dayOf, inRange, type Dataset } from "./data";

export const ROLE_KEY = "tut.demo.role";
export const BUDGETS_KEY = "tut.demo.budgets";

/** Knobs the demo menu turns. */
export const demoSettings = {
  /** Pretend network time, so loading states are visible. */
  delayMs: 150,
  /** When true every data request fails with a 503, to show the error state. */
  failRequests: false,
};

export type DemoRole = "admin" | "member";

// ---- Storage (localStorage when allowed, memory otherwise)

const memory = new Map<string, string>();

function readStore(key: string): string | null {
  try {
    return localStorage.getItem(key) ?? memory.get(key) ?? null;
  } catch {
    return memory.get(key) ?? null;
  }
}

function writeStore(key: string, value: string): void {
  memory.set(key, value);
  try {
    localStorage.setItem(key, value);
  } catch {
    // storage blocked: the in-memory copy still works for this visit
  }
}

function removeStore(key: string): void {
  memory.delete(key);
  try {
    localStorage.removeItem(key);
  } catch {
    // nothing stored
  }
}

export function getRole(): DemoRole {
  return readStore(ROLE_KEY) === "member" ? "member" : "admin";
}

export function setRole(role: DemoRole): void {
  writeStore(ROLE_KEY, role);
}

/** Forget the visitor's budgets and role; the next request starts from the seed. */
export function resetDemoState(): void {
  removeStore(BUDGETS_KEY);
  removeStore(ROLE_KEY);
  budgets = null;
  demoSettings.failRequests = false;
}

// ---- Dataset

let dataset: Dataset | null = null;

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

export function getDataset(): Dataset {
  const t = today();
  if (!dataset || dataset.today !== t) dataset = buildDataset(t);
  return dataset;
}

// ---- Aggregation helpers

const round6 = (n: number) => Math.round(n * 1e6) / 1e6;

function emptyTotals(): Totals {
  return { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheCreationTokens: 0, costUsd: 0, linesAdded: 0, linesRemoved: 0 };
}

function add(t: Totals, e: UsageEventDetail): void {
  t.inputTokens += e.inputTokens;
  t.outputTokens += e.outputTokens;
  t.cacheReadTokens += e.cacheReadTokens;
  t.cacheCreationTokens += e.cacheCreationTokens;
  t.costUsd += e.costUsd;
  t.linesAdded += e.linesAdded ?? 0;
  t.linesRemoved += e.linesRemoved ?? 0;
}

function sum(events: UsageEventDetail[]): Totals {
  const t = emptyTotals();
  for (const e of events) add(t, e);
  t.costUsd = round6(t.costUsd);
  return t;
}

function groupBy<K>(events: UsageEventDetail[], key: (e: UsageEventDetail) => K): Map<K, Totals> {
  const out = new Map<K, Totals>();
  for (const e of events) {
    const k = key(e);
    let t = out.get(k);
    if (!t) out.set(k, (t = emptyTotals()));
    add(t, e);
  }
  for (const t of out.values()) t.costUsd = round6(t.costUsd);
  return out;
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

function dayRange(q: URLSearchParams): { from: string; to: string } {
  const from = q.get("from");
  const to = q.get("to");
  if (!from || !to || !ISO_DAY.test(from) || !ISO_DAY.test(to)) {
    throw new ApiError(400, "from and to are required dates (YYYY-MM-DD)");
  }
  return { from, to };
}

function daysBetweenInclusive(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
}

// ---- Budgets

interface StoredBudget {
  id: string;
  scopeType: "PROJECT" | "TEAM";
  scopeId: string;
  monthlyLimitUsd: number;
  webhookUrl: string;
  enabled: boolean;
  createdAt: string;
  /** Part of the starting data: its alerts show as delivered. */
  seeded: boolean;
}

let budgets: StoredBudget[] | null = null;

function monthStart(): string {
  return `${today().slice(0, 8)}01`;
}

function scopeSpend(scopeType: "PROJECT" | "TEAM", scopeId: string): number {
  const events = inRange(getDataset().events, monthStart(), today()).filter((e) =>
    scopeType === "PROJECT" ? e.projectId === scopeId : e.teamId === scopeId,
  );
  return round6(events.reduce((s, e) => s + e.costUsd, 0));
}

/** Average daily spend of a scope over the last 30 days. */
function recentDailySpend(scopeType: "PROJECT" | "TEAM", scopeId: string): number {
  const ds = getDataset();
  const events = inRange(ds.events, addDays(ds.today, -29), ds.today).filter((e) =>
    scopeType === "PROJECT" ? e.projectId === scopeId : e.teamId === scopeId,
  );
  return events.reduce((s, e) => s + e.costUsd, 0) / 30;
}

function seedBudgets(): StoredBudget[] {
  const ds = getDataset();
  const make = (scopeType: "PROJECT" | "TEAM", scopeId: string, ratio: number): StoredBudget => ({
    id: crypto.randomUUID(),
    scopeType,
    scopeId,
    // Pick the limit so the budget starts at the wanted share of this month's
    // spend. Early in the month there is little spend to go by, so assume at
    // least five days of it.
    monthlyLimitUsd: Math.max(
      10,
      Math.round(
        Math.max(scopeSpend(scopeType, scopeId), recentDailySpend(scopeType, scopeId) * 5) / ratio / 10,
      ) * 10,
    ),
    webhookUrl: "https://hooks.example.com/budget-alerts",
    enabled: true,
    createdAt: new Date(Date.parse(`${ds.today}T00:00:00Z`) - 20 * 86_400_000).toISOString(),
    seeded: true,
  });
  return [
    make("PROJECT", "proj-checkout-api", 0.84),
    make("PROJECT", "proj-code-review-bot", 1.12),
    make("TEAM", "team-search", 0.5),
  ];
}

function loadBudgets(): StoredBudget[] {
  if (budgets) return budgets;
  const raw = readStore(BUDGETS_KEY);
  if (raw) {
    try {
      budgets = JSON.parse(raw) as StoredBudget[];
      return budgets;
    } catch {
      // corrupt copy: start again from the seed
    }
  }
  budgets = seedBudgets();
  saveBudgets();
  return budgets;
}

function saveBudgets(): void {
  writeStore(BUDGETS_KEY, JSON.stringify(budgets ?? []));
}

function toBudget(b: StoredBudget): Budget {
  const { seeded: _seeded, ...rest } = b;
  return { ...rest, currentSpendUsd: scopeSpend(b.scopeType, b.scopeId) };
}

function alertsFor(b: StoredBudget): BudgetAlert[] {
  const spend = scopeSpend(b.scopeType, b.scopeId);
  const out: BudgetAlert[] = [];
  for (const pct of [100, 80]) {
    if (b.monthlyLimitUsd > 0 && spend >= (b.monthlyLimitUsd * pct) / 100) {
      out.push({
        id: `${b.id}-${monthStart()}-${pct}`,
        periodStart: monthStart(),
        thresholdPct: pct,
        spendUsd: spend,
        limitUsd: b.monthlyLimitUsd,
        // The demo never calls a webhook. Seeded alerts look delivered so the
        // page shows both states; alerts for budgets the visitor adds stay pending.
        status: b.seeded ? "SENT" : "PENDING",
        attempts: b.seeded ? 1 : 0,
        createdAt: new Date().toISOString(),
        sentAt: b.seeded ? new Date().toISOString() : null,
      });
    }
  }
  return out;
}

const PRIVATE_HOST = /^(localhost|.*\.local|.*\.internal|0\.0\.0\.0|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/i;

/** Same rules the API applies, so the form shows the same messages. */
function checkWebhook(url: unknown): string {
  if (typeof url !== "string" || !url.trim()) {
    throw new ApiError(400, "Validation failed", { webhookUrl: "must not be blank" });
  }
  let u: URL;
  try {
    u = new URL(url.trim());
  } catch {
    throw new ApiError(400, "webhookUrl is not a valid URL");
  }
  if (u.protocol !== "https:") throw new ApiError(400, "webhookUrl must use https");
  if (u.username || u.password) throw new ApiError(400, "webhookUrl must be an absolute URL without credentials");
  const host = u.hostname.replace(/^\[|\]$/g, "");
  if (PRIVATE_HOST.test(host) || host === "::1") {
    throw new ApiError(400, "webhookUrl must not point to a private or local address");
  }
  return u.toString();
}

function checkLimit(value: unknown): number {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0.01) {
    throw new ApiError(400, "Validation failed", { monthlyLimitUsd: "must be greater than or equal to 0.01" });
  }
  return n;
}

function requireAdmin(): void {
  if (getRole() !== "admin") throw new ApiError(403, "You do not have access to this.");
}

function findBudget(id: string): StoredBudget {
  const b = loadBudgets().find((x) => x.id === id);
  if (!b) throw new ApiError(404, "Budget not found.");
  return b;
}

// ---- Routing

interface Body {
  scopeType?: "PROJECT" | "TEAM";
  scopeId?: string;
  monthlyLimitUsd?: unknown;
  webhookUrl?: unknown;
  enabled?: boolean;
}

function route(method: string, pathname: string, q: URLSearchParams, body: Body): unknown {
  const ds = getDataset();

  if (method === "POST" && pathname === "/auth/token") {
    return { token: "demo", tokenType: "Bearer", expiresInSeconds: 43_200 };
  }
  if (method === "POST" && pathname === "/auth/logout") return undefined;
  if (method === "GET" && pathname === "/auth/me") {
    const admin = getRole() === "admin";
    const m = ds.members[admin ? 0 : 1];
    const me: Me = { memberId: m.id, orgId: "org-acme", email: m.email, roles: admin ? ["ADMIN", "MEMBER"] : ["MEMBER"] };
    return me;
  }

  if (demoSettings.failRequests) throw new ApiError(503, "The server did not respond (503).");

  if (method === "GET" && pathname === "/analytics/directory") {
    const dir: Directory = {
      projects: ds.projects,
      teams: ds.teams,
      members: ds.members.map((m) => ({ id: m.id, displayName: m.displayName, email: m.email, teamId: m.teamId })),
    };
    return dir;
  }

  if (method === "GET" && pathname.startsWith("/analytics/")) {
    const { from, to } = dayRange(q);
    const events = inRange(ds.events, from, to);
    switch (pathname) {
      case "/analytics/projects": {
        const out: ProjectAnalytics[] = [...groupBy(events, (e) => e.projectId)].map(([id, t]) => ({
          projectId: id,
          name: ds.projects.find((p) => p.id === id)?.name ?? id,
          inputTokens: t.inputTokens,
          outputTokens: t.outputTokens,
          costUsd: t.costUsd,
          linesAdded: t.linesAdded,
          linesRemoved: t.linesRemoved,
        }));
        return out.sort((a, b) => b.costUsd - a.costUsd);
      }
      case "/analytics/members": {
        const out: MemberAnalytics[] = [...groupBy(events, (e) => e.memberId)].map(([id, t]) => {
          const m = ds.members.find((x) => x.id === id)!;
          return {
            memberId: id,
            displayName: m.displayName,
            email: m.email,
            inputTokens: t.inputTokens,
            outputTokens: t.outputTokens,
            costUsd: t.costUsd,
            linesAdded: t.linesAdded,
            linesRemoved: t.linesRemoved,
          };
        });
        return out.sort((a, b) => b.costUsd - a.costUsd);
      }
      case "/analytics/teams": {
        // Like the API, members without a team are not in this view
        const out: TeamAnalytics[] = [...groupBy(events.filter((e) => e.teamId), (e) => e.teamId as string)].map(
          ([id, t]) => ({
            teamId: id,
            name: ds.teams.find((x) => x.id === id)?.name ?? id,
            memberCount: ds.members.filter((m) => m.teamId === id).length,
            inputTokens: t.inputTokens,
            outputTokens: t.outputTokens,
            costUsd: t.costUsd,
          }),
        );
        return out.sort((a, b) => b.costUsd - a.costUsd);
      }
      case "/analytics/daily": {
        const out: DailyAnalytics[] = [...groupBy(events, dayOf)].map(([date, t]) => ({
          date,
          inputTokens: t.inputTokens,
          outputTokens: t.outputTokens,
          costUsd: t.costUsd,
        }));
        return out.sort((a, b) => (a.date < b.date ? -1 : 1));
      }
      case "/analytics/models": {
        const projectId = q.get("projectId");
        const scoped = projectId ? events.filter((e) => e.projectId === projectId) : events;
        const out: ModelAnalytics[] = [...groupBy(scoped, (e) => e.model)].map(([model, t]) => ({
          model,
          inputTokens: t.inputTokens,
          outputTokens: t.outputTokens,
          cacheReadTokens: t.cacheReadTokens,
          cacheCreationTokens: t.cacheCreationTokens,
          costUsd: t.costUsd,
        }));
        return out.sort((a, b) => b.costUsd - a.costUsd);
      }
      case "/analytics/summary": {
        if (from > to) throw new ApiError(400, "from must not be after to");
        const n = daysBetweenInclusive(from, to);
        const previousTo = addDays(from, -1);
        const previousFrom = addDays(previousTo, -(n - 1));
        const summary: Summary = {
          from,
          to,
          current: sum(events),
          previousFrom,
          previousTo,
          previous: sum(inRange(ds.events, previousFrom, previousTo)),
        };
        return summary;
      }
    }
  }

  if (method === "GET" && pathname === "/usage") {
    const from = Date.parse(q.get("from") ?? "");
    const to = Date.parse(q.get("to") ?? "");
    if (Number.isNaN(from) || Number.isNaN(to)) throw new ApiError(400, "from and to are required");
    const projectId = q.get("projectId");
    const memberId = q.get("memberId");
    const model = q.get("model");
    const matching = ds.events.filter((e) => {
      const t = Date.parse(e.eventTime);
      return (
        t >= from &&
        t < to &&
        (!projectId || e.projectId === projectId) &&
        (!memberId || e.memberId === memberId) &&
        (!model || e.model === model)
      );
    });
    const size = Math.min(Math.max(Number(q.get("size")) || 50, 1), 200);
    const page = Math.max(Number(q.get("page")) || 0, 0);
    const result: Page<UsageEventDetail> = {
      content: matching.slice(page * size, page * size + size),
      page,
      size,
      totalElements: matching.length,
      totalPages: Math.ceil(matching.length / size),
    };
    return result;
  }

  if (pathname === "/budgets" || pathname.startsWith("/budgets/")) {
    requireAdmin();
    const all = loadBudgets();

    if (pathname === "/budgets" && method === "GET") return all.map(toBudget);

    if (pathname === "/budgets" && method === "POST") {
      const scopeType = body.scopeType;
      const scopeId = body.scopeId;
      if (scopeType !== "PROJECT" && scopeType !== "TEAM") {
        throw new ApiError(400, "Validation failed", { scopeType: "must not be null" });
      }
      if (!scopeId) throw new ApiError(400, "Validation failed", { scopeId: "must not be null" });
      const limit = checkLimit(body.monthlyLimitUsd);
      const exists = scopeType === "PROJECT" ? ds.projects.some((p) => p.id === scopeId) : ds.teams.some((t) => t.id === scopeId);
      if (!exists) throw new ApiError(404, `${scopeType === "PROJECT" ? "Project" : "Team"} not found.`);
      const webhookUrl = checkWebhook(body.webhookUrl);
      if (all.some((b) => b.scopeType === scopeType && b.scopeId === scopeId)) {
        throw new ApiError(409, "A budget already exists for this scope.");
      }
      const created: StoredBudget = {
        id: crypto.randomUUID(),
        scopeType,
        scopeId,
        monthlyLimitUsd: limit,
        webhookUrl,
        enabled: body.enabled ?? true,
        createdAt: new Date().toISOString(),
        seeded: false,
      };
      all.push(created);
      saveBudgets();
      return toBudget(created);
    }

    const m = /^\/budgets\/([^/]+)(\/alerts)?$/.exec(pathname);
    if (m) {
      const b = findBudget(m[1]);
      if (m[2] && method === "GET") return alertsFor(b);
      if (!m[2] && method === "PUT") {
        b.monthlyLimitUsd = checkLimit(body.monthlyLimitUsd);
        b.webhookUrl = checkWebhook(body.webhookUrl);
        b.enabled = body.enabled ?? b.enabled;
        saveBudgets();
        return toBudget(b);
      }
      if (!m[2] && method === "DELETE") {
        budgets = all.filter((x) => x.id !== b.id);
        saveBudgets();
        return undefined;
      }
    }
  }

  throw new ApiError(404, "Not found.");
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** Answers one API call. {@code path} includes the query string, like the real request. */
export async function handle<T>(path: string, init?: RequestInit): Promise<T> {
  if (demoSettings.delayMs > 0) await sleep(demoSettings.delayMs);
  const url = new URL(path, "http://demo.invalid");
  const method = (init?.method ?? "GET").toUpperCase();
  const body: Body = typeof init?.body === "string" && init.body ? (JSON.parse(init.body) as Body) : {};
  return route(method, url.pathname, url.searchParams, body) as T;
}
