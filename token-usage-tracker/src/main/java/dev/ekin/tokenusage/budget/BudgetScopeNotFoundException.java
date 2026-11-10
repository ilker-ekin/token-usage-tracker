package dev.ekin.tokenusage.budget;

import java.util.UUID;

/** The project or team a budget should cap is missing or in another org. */
public class BudgetScopeNotFoundException extends RuntimeException {

	public BudgetScopeNotFoundException(BudgetScope scopeType, UUID scopeId) {
		super((scopeType == BudgetScope.PROJECT ? "Project" : "Team") + " not found: " + scopeId);
	}

}
