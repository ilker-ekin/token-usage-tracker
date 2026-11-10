// Synthetic data for the public demo build. Everything here is made up: the
// people, teams, projects and prices are illustrative and match no real
// customer. The generator is seeded, so a given day always gets the same events.

import type { UsageEventDetail } from "../api";

export interface DemoTeam {
  id: string;
  name: string;
}
export interface DemoProject {
  id: string;
  name: string;
}
export interface DemoMember {
  id: string;
  displayName: string;
  email: string;
  teamId: string | null;
}
export interface DemoModel {
  id: string;
  /** Illustrative USD per 1,000 tokens, not any provider's current price list. */
  inputPer1k: number;
  outputPer1k: number;
  cacheReadPer1k: number;
  cacheCreationPer1k: number;
}

export interface Dataset {
  today: string;
  teams: DemoTeam[];
  projects: DemoProject[];
  members: DemoMember[];
  models: DemoModel[];
  /** Newest first. */
  events: UsageEventDetail[];
}

/** How many days back the generated history reaches. */
export const HISTORY_DAYS = 90;

export const TEAMS: DemoTeam[] = [
  { id: "team-platform", name: "Platform" },
  { id: "team-search", name: "Search" },
  { id: "team-growth", name: "Growth" },
  { id: "team-support", name: "Support Tools" },
  { id: "team-research", name: "Research" },
];

export const PROJECTS: DemoProject[] = [
  { id: "proj-checkout-api", name: "Checkout API" },
  { id: "proj-code-review-bot", name: "Code Review Bot" },
  { id: "proj-search-ranking", name: "Search Ranking" },
  { id: "proj-docs-assistant", name: "Docs Assistant" },
  { id: "proj-support-triage", name: "Support Triage" },
  { id: "proj-growth-experiments", name: "Growth Experiments" },
  { id: "proj-data-pipeline", name: "Data Pipeline" },
  { id: "proj-eval-harness", name: "Eval Harness" },
  { id: "proj-mobile-app", name: "Mobile App" },
];

export const MODELS: DemoModel[] = [
  { id: "claude-opus-4-8", inputPer1k: 0.015, outputPer1k: 0.075, cacheReadPer1k: 0.0015, cacheCreationPer1k: 0.01875 },
  { id: "claude-sonnet-4-6", inputPer1k: 0.003, outputPer1k: 0.015, cacheReadPer1k: 0.0003, cacheCreationPer1k: 0.00375 },
  { id: "claude-haiku-4-5", inputPer1k: 0.001, outputPer1k: 0.005, cacheReadPer1k: 0.0001, cacheCreationPer1k: 0.00125 },
  { id: "gpt-4o", inputPer1k: 0.0025, outputPer1k: 0.01, cacheReadPer1k: 0.00125, cacheCreationPer1k: 0 },
  { id: "gpt-4o-mini", inputPer1k: 0.00015, outputPer1k: 0.0006, cacheReadPer1k: 0.000075, cacheCreationPer1k: 0 },
];

const FIRST_NAMES = [
  "Ada", "Brian", "Chloe", "Dmitri", "Elena", "Farid", "Grace", "Hiro", "Ines", "Jonas",
  "Kavya", "Liam", "Maya", "Nikolai", "Olu", "Priya", "Quinn", "Rosa", "Sven", "Tara",
  "Uma", "Victor", "Wen", "Ximena", "Yusuf", "Zoe", "Amir", "Bea",
];
const LAST_NAMES = [
  "Lovelace", "Okafor", "Nguyen", "Petrov", "Santos", "Haddad", "Kim", "Tanaka", "Moreau", "Larsen",
  "Iyer", "Walsh", "Cohen", "Volkov", "Adeyemi", "Sharma", "Reyes", "Silva", "Berg", "Singh",
  "Rao", "Costa", "Zhang", "Torres", "Demir", "Fischer", "Karimi", "Rossi",
];

/** Small deterministic PRNG (mulberry32): same seed, same sequence. */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function addDays(isoDay: string, days: number): string {
  const d = new Date(`${isoDay}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** What one event costs: the four token kinds at the model's illustrative rates, rounded once. */
export function eventCost(m: DemoModel, input: number, output: number, cacheRead: number, cacheCreation: number): number {
  const usd =
    (input * m.inputPer1k + output * m.outputPer1k + cacheRead * m.cacheReadPer1k + cacheCreation * m.cacheCreationPer1k) / 1000;
  return Math.round(usd * 1e6) / 1e6;
}

function pickWeighted<T>(rand: () => number, items: T[], weights: number[]): T {
  const total = weights.reduce((a, b) => a + b, 0);
  let r = rand() * total;
  for (let i = 0; i < items.length; i++) {
    r -= weights[i];
    if (r <= 0) return items[i];
  }
  return items[items.length - 1];
}

function buildMembers(): DemoMember[] {
  return FIRST_NAMES.map((first, i) => {
    const last = LAST_NAMES[i];
    // The last two members have no team, like a real org with contractors
    const team = i >= FIRST_NAMES.length - 2 ? null : TEAMS[i % TEAMS.length];
    return {
      id: `member-${String(i + 1).padStart(2, "0")}`,
      displayName: `${first} ${last}`,
      email: `${first}.${last}@acme.example`.toLowerCase(),
      teamId: team ? team.id : null,
    };
  });
}

/**
 * Builds {@link HISTORY_DAYS} days of events ending on {@code today} (UTC).
 * Weekdays are busier than weekends and usage grows by about 40% across the
 * window, so the charts have a shape.
 */
export function buildDataset(today: string): Dataset {
  const rand = mulberry32(20260401);
  const members = buildMembers();

  // Each member works on two or three projects, with a favourite
  const memberProjects = new Map<string, { projects: DemoProject[]; weights: number[] }>();
  const memberActivity = new Map<string, number>();
  for (const m of members) {
    const count = 2 + Math.floor(rand() * 2);
    const chosen: DemoProject[] = [];
    while (chosen.length < count) {
      const p = PROJECTS[Math.floor(rand() * PROJECTS.length)];
      if (!chosen.includes(p)) chosen.push(p);
    }
    memberProjects.set(m.id, { projects: chosen, weights: chosen.map((_, i) => (i === 0 ? 5 : 2)) });
    // A few heavy users and many light ones
    memberActivity.set(m.id, 0.4 + rand() * rand() * 3.2);
  }

  const modelWeights = [0.07, 0.25, 0.28, 0.12, 0.28];
  const events: UsageEventDetail[] = [];
  let seq = 0;

  for (let back = HISTORY_DAYS - 1; back >= 0; back--) {
    const day = addDays(today, -back);
    const weekday = new Date(`${day}T00:00:00Z`).getUTCDay(); // 0 Sunday
    const weekend = weekday === 0 || weekday === 6;
    const growth = 0.8 + 0.4 * ((HISTORY_DAYS - 1 - back) / (HISTORY_DAYS - 1)) + 0.2;
    for (const member of members) {
      const base = weekend ? 1.6 : 11;
      const n = Math.round(base * (memberActivity.get(member.id) ?? 1) * growth * (0.6 + rand() * 0.8));
      const mp = memberProjects.get(member.id)!;
      for (let i = 0; i < n; i++) {
        const project = pickWeighted(rand, mp.projects, mp.weights);
        const model = pickWeighted(rand, MODELS, modelWeights);
        const anthropic = model.id.startsWith("claude");
        const heavy = model.id === "claude-opus-4-8";
        // Agent-style calls: long prompts, mostly served from the prompt cache
        const inputTokens = Math.round(2000 + rand() * rand() * (heavy ? 200_000 : 330_000));
        const outputTokens = Math.round(500 + rand() * rand() * (heavy ? 50_000 : 30_000));
        const cacheReadTokens = rand() < 0.55 ? Math.round(rand() * (anthropic ? 560_000 : 170_000)) : 0;
        const cacheCreationTokens = anthropic && rand() < 0.2 ? Math.round(rand() * 48_000) : 0;
        const codeAssist = rand() < 0.4;
        const secondsIntoDay = Math.floor((weekend ? 9 + rand() * 12 : 8 + rand() * 11) * 3600);
        const eventTime = new Date(Date.parse(`${day}T00:00:00Z`) + secondsIntoDay * 1000).toISOString();
        events.push({
          id: `evt-${String(++seq).padStart(6, "0")}`,
          memberId: member.id,
          teamId: member.teamId,
          projectId: project.id,
          model: model.id,
          inputTokens,
          outputTokens,
          cacheReadTokens,
          cacheCreationTokens,
          linesAdded: codeAssist ? Math.round(rand() * 120) : null,
          linesRemoved: codeAssist ? Math.round(rand() * 40) : null,
          costUsd: eventCost(model, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens),
          eventTime,
        });
      }
    }
  }

  events.sort((a, b) => (a.eventTime < b.eventTime ? 1 : a.eventTime > b.eventTime ? -1 : 0));
  return { today, teams: TEAMS, projects: PROJECTS, members, models: MODELS, events };
}

/** The UTC day of an event. */
export const dayOf = (e: UsageEventDetail): string => e.eventTime.slice(0, 10);

/** Events whose UTC day is within [from, to], both inclusive. */
export function inRange(events: UsageEventDetail[], from: string, to: string): UsageEventDetail[] {
  return events.filter((e) => {
    const d = dayOf(e);
    return d >= from && d <= to;
  });
}
