package dev.ekin.tokenusage.budget;

public class DuplicateBudgetException extends RuntimeException {

	public DuplicateBudgetException() {
		super("A budget for this project or team already exists");
	}

}
