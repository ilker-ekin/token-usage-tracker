import { Budget } from "./api";

/** Month-to-date spend as a share of the limit. */
export function budgetRatio(b: Budget): number {
  return b.monthlyLimitUsd > 0 ? b.currentSpendUsd / b.monthlyLimitUsd : 0;
}
