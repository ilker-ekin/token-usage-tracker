// The session lives in an httpOnly cookie set by POST /auth/token, so no
// script on this page can read the token. Earlier builds kept it in
// localStorage; drop that copy if one is left behind.
try {
  localStorage.removeItem("tut.jwt");
} catch {
  // storage unavailable: nothing to clean up
}

/** Sent on every request. The API requires it on cookie-authenticated writes (CSRF). */
const CSRF_HEADERS = { "X-Requested-With": "XMLHttpRequest" };

/** Fired when a request finds the session gone; the session gate asks to sign in again. */
export const SESSION_EXPIRED = "tut:session-expired";
/** Fired after signing in again in place; open views reload their data. */
export const SESSION_RESTORED = "tut:session-restored";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    /** Field errors from a 400 ProblemDetail, keyed by field name. */
    public fieldErrors: Record<string, string> = {},
    public retryAfterSeconds?: number,
  ) {
    super(message);
  }
}

interface ProblemDetail {
  detail?: string;
  errors?: Record<string, string>;
}

async function problemOf(res: Response): Promise<ProblemDetail> {
  try {
    return (await res.json()) as ProblemDetail;
  } catch {
    return {};
  }
}

function describe(status: number): string {
  if (status === 403) return "You do not have access to this.";
  if (status === 404) return "Not found.";
  if (status === 429) return "Too many attempts. Try again later.";
  if (status >= 500) return `The server did not respond (${status}).`;
  return `Request failed (${status}).`;
}

async function request<T>(
  path: string,
  init?: RequestInit,
  { onUnauthorized = "expire" }: { onUnauthorized?: "expire" | "throw" } = {},
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        ...CSRF_HEADERS,
        ...init?.headers,
      },
    });
  } catch {
    throw new ApiError(0, "The server could not be reached. Check your connection.");
  }
  if (res.status === 401) {
    if (onUnauthorized === "expire") window.dispatchEvent(new Event(SESSION_EXPIRED));
    throw new ApiError(401, "Your session has ended.");
  }
  if (!res.ok) {
    const problem = await problemOf(res);
    const retryAfter = Number(res.headers.get("Retry-After")) || undefined;
    throw new ApiError(res.status, problem.detail ?? describe(res.status), problem.errors ?? {}, retryAfter);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

// ---- Types

export interface TokenResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface Me {
  memberId: string;
  orgId: string;
  email: string;
  roles: string[];
}

export interface ProjectAnalytics {
  projectId: string;
  name: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  linesAdded: number;
  linesRemoved: number;
}

export interface MemberAnalytics {
  memberId: string;
  displayName: string;
  email: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  linesAdded: number;
  linesRemoved: number;
}

export interface TeamAnalytics {
  teamId: string;
  name: string;
  memberCount: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

export interface DailyAnalytics {
  date: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

export interface ModelAnalytics {
  model: string;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  costUsd: number;
}

export interface Totals {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  costUsd: number;
  linesAdded: number;
  linesRemoved: number;
}

export interface Summary {
  from: string;
  to: string;
  current: Totals;
  previousFrom: string;
  previousTo: string;
  previous: Totals;
}

export interface Directory {
  projects: { id: string; name: string }[];
  teams: { id: string; name: string }[];
  members: { id: string; displayName: string; email: string; teamId: string | null }[];
}

export interface UsageEventDetail {
  id: string;
  memberId: string;
  teamId: string | null;
  projectId: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  linesAdded: number | null;
  linesRemoved: number | null;
  costUsd: number;
  eventTime: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type BudgetScope = "PROJECT" | "TEAM";

export interface Budget {
  id: string;
  scopeType: BudgetScope;
  scopeId: string;
  monthlyLimitUsd: number;
  webhookUrl: string;
  enabled: boolean;
  currentSpendUsd: number;
  createdAt: string;
}

export type AlertStatus = "PENDING" | "SENDING" | "SENT" | "FAILED";

export interface BudgetAlert {
  id: string;
  periodStart: string;
  thresholdPct: number;
  spendUsd: number;
  limitUsd: number;
  status: AlertStatus;
  attempts: number;
  createdAt: string;
  sentAt: string | null;
}

export interface BudgetInput {
  scopeType?: BudgetScope;
  scopeId?: string;
  monthlyLimitUsd: number;
  webhookUrl: string;
  enabled: boolean;
}

// ---- Auth

/** Sets the session cookie; the token in the body is not kept. */
export async function login(email: string, password: string): Promise<void> {
  await request<TokenResponse>(
    "/auth/token",
    { method: "POST", body: JSON.stringify({ email, password }) },
    { onUnauthorized: "throw" },
  );
}

export async function logout(): Promise<void> {
  await request<void>("/auth/logout", { method: "POST" }, { onUnauthorized: "throw" });
}

/** The current member, or a 401 ApiError when there is no valid session. */
export const fetchMe = () => request<Me>("/auth/me", undefined, { onUnauthorized: "throw" });

// ---- Analytics

const range = (from: string, to: string) => `from=${from}&to=${to}`;

export const fetchProjects = (from: string, to: string) =>
  request<ProjectAnalytics[]>(`/analytics/projects?${range(from, to)}`);
export const fetchMembers = (from: string, to: string) =>
  request<MemberAnalytics[]>(`/analytics/members?${range(from, to)}`);
export const fetchTeams = (from: string, to: string) =>
  request<TeamAnalytics[]>(`/analytics/teams?${range(from, to)}`);
export const fetchDaily = (from: string, to: string) =>
  request<DailyAnalytics[]>(`/analytics/daily?${range(from, to)}`);
export const fetchModels = (from: string, to: string, projectId?: string) =>
  request<ModelAnalytics[]>(
    `/analytics/models?${range(from, to)}${projectId ? `&projectId=${projectId}` : ""}`,
  );
export const fetchSummary = (from: string, to: string) =>
  request<Summary>(`/analytics/summary?${range(from, to)}`);
export const fetchDirectory = () => request<Directory>("/analytics/directory");

// ---- Raw events

export interface UsageQuery {
  from: string;
  to: string;
  projectId?: string;
  memberId?: string;
  model?: string;
  page?: number;
  size?: number;
}

/** {@code from} and {@code to} are inclusive UTC days, like the analytics ranges. */
export function fetchUsage(q: UsageQuery) {
  const params = new URLSearchParams({
    from: `${q.from}T00:00:00Z`,
    to: `${nextDay(q.to)}T00:00:00Z`,
    page: String(q.page ?? 0),
    size: String(q.size ?? 50),
  });
  if (q.projectId) params.set("projectId", q.projectId);
  if (q.memberId) params.set("memberId", q.memberId);
  if (q.model) params.set("model", q.model);
  return request<Page<UsageEventDetail>>(`/usage?${params}`);
}

function nextDay(isoDay: string): string {
  const d = new Date(`${isoDay}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

// ---- Budgets (admin only)

export const fetchBudgets = () => request<Budget[]>("/budgets");
export const fetchBudgetAlerts = (id: string) => request<BudgetAlert[]>(`/budgets/${id}/alerts`);
export const createBudget = (input: BudgetInput) =>
  request<Budget>("/budgets", { method: "POST", body: JSON.stringify(input) });
export const updateBudget = (id: string, input: BudgetInput) =>
  request<Budget>(`/budgets/${id}`, {
    method: "PUT",
    body: JSON.stringify({
      monthlyLimitUsd: input.monthlyLimitUsd,
      webhookUrl: input.webhookUrl,
      enabled: input.enabled,
    }),
  });
export const deleteBudget = (id: string) => request<void>(`/budgets/${id}`, { method: "DELETE" });
