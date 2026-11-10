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

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(
  path: string,
  init?: RequestInit,
  { redirectOn401 = true }: { redirectOn401?: boolean } = {},
): Promise<T> {
  const res = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      ...CSRF_HEADERS,
      ...init?.headers,
    },
  });
  if (res.status === 401) {
    if (redirectOn401) window.location.href = "/login";
    throw new ApiError(401, "Session expired");
  }
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed: ${res.status}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

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

/** Sets the session cookie; the token in the body is not kept. */
export async function login(email: string, password: string): Promise<void> {
  await request<TokenResponse>(
    "/auth/token",
    { method: "POST", body: JSON.stringify({ email, password }) },
    { redirectOn401: false },
  );
}

export async function logout(): Promise<void> {
  await request<void>("/auth/logout", { method: "POST" }, { redirectOn401: false });
}

/** The current member, or a 401 ApiError when there is no valid session. */
export const fetchMe = () => request<Me>("/auth/me", undefined, { redirectOn401: false });

const range = (from: string, to: string) => `from=${from}&to=${to}`;

export const fetchProjects = (from: string, to: string) =>
  request<ProjectAnalytics[]>(`/analytics/projects?${range(from, to)}`);
export const fetchMembers = (from: string, to: string) =>
  request<MemberAnalytics[]>(`/analytics/members?${range(from, to)}`);
export const fetchTeams = (from: string, to: string) =>
  request<TeamAnalytics[]>(`/analytics/teams?${range(from, to)}`);
export const fetchDaily = (from: string, to: string) =>
  request<DailyAnalytics[]>(`/analytics/daily?${range(from, to)}`);
