const TOKEN_KEY = "tut.jwt";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const res = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (res.status === 401) {
    clearToken();
    window.location.href = "/login";
    throw new ApiError(401, "Session expired");
  }
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed: ${res.status}`);
  }
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

export async function login(email: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>("/auth/token", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
}

export const fetchMe = () => request<Me>("/auth/me");

const range = (from: string, to: string) => `from=${from}&to=${to}`;

export const fetchProjects = (from: string, to: string) =>
  request<ProjectAnalytics[]>(`/analytics/projects?${range(from, to)}`);
export const fetchMembers = (from: string, to: string) =>
  request<MemberAnalytics[]>(`/analytics/members?${range(from, to)}`);
export const fetchTeams = (from: string, to: string) =>
  request<TeamAnalytics[]>(`/analytics/teams?${range(from, to)}`);
export const fetchDaily = (from: string, to: string) =>
  request<DailyAnalytics[]>(`/analytics/daily?${range(from, to)}`);
