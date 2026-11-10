package dev.ekin.tokenusage.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * Checks every enabled budget against its month-to-date spend once a minute
 * and delivers the alerts that raises.
 *
 * <p>Evaluation runs here rather than in the ingest path. On ingest it would
 * add a query to every event, and two concurrent ingests could each read a
 * total from before the other committed and both miss the crossing. Here the
 * cost depends on the number of budgets, not on traffic, and a crossing is
 * noticed at most one interval late. The nightly rollup recompute is far too
 * infrequent for an alert.
 */
@Component
public class BudgetEvaluationJob {

	private static final Logger log = LoggerFactory.getLogger(BudgetEvaluationJob.class);

	private final BudgetAlertRepository budgetAlertRepository;
	private final BudgetAlertSender budgetAlertSender;
	private final TransactionTemplate transactionTemplate;

	public BudgetEvaluationJob(BudgetAlertRepository budgetAlertRepository,
			BudgetAlertSender budgetAlertSender, TransactionTemplate transactionTemplate) {
		this.budgetAlertRepository = budgetAlertRepository;
		this.budgetAlertSender = budgetAlertSender;
		this.transactionTemplate = transactionTemplate;
	}

	@Scheduled(fixedDelayString = "${app.budgets.evaluation-interval:PT1M}",
			initialDelayString = "${app.budgets.evaluation-initial-delay:PT1M}")
	public void run() {
		int raised = evaluate();
		int sent = budgetAlertSender.sendPending();
		if (raised > 0 || sent > 0) {
			log.info("Budget evaluation: {} alert(s) raised, {} delivered", raised, sent);
		}
	}

	/** Evaluates the current month (UTC). @return how many new alerts this run raised */
	public int evaluate() {
		return evaluate(BudgetService.currentMonthStart());
	}

	int evaluate(LocalDate monthStart) {
		return transactionTemplate.execute(status ->
				budgetAlertRepository.raiseCrossedThresholds(monthStart));
	}

}
