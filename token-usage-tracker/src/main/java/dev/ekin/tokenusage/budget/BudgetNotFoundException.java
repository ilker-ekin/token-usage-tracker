package dev.ekin.tokenusage.budget;

import java.util.UUID;

/**
 * Thrown both when a budget does not exist and when it belongs to another
 * org, so callers cannot probe for other tenants' budget ids.
 */
public class BudgetNotFoundException extends RuntimeException {

	public BudgetNotFoundException(UUID budgetId) {
		super("Budget not found: " + budgetId);
	}

}
