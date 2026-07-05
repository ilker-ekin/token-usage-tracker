package dev.ekin.tokenusage.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageEventRepository
		extends JpaRepository<UsageEvent, UUID>, JpaSpecificationExecutor<UsageEvent> {

	interface UsageAggregate {
		UUID getOrgId();
		UUID getTeamId();
		UUID getMemberId();
		UUID getProjectId();
		long getInputTokens();
		long getOutputTokens();
		BigDecimal getCostUsd();
		long getLinesAdded();
		long getLinesRemoved();
	}

	@Query("""
			select e.orgId as orgId, e.teamId as teamId, e.memberId as memberId, e.projectId as projectId,
			       sum(e.inputTokens) as inputTokens, sum(e.outputTokens) as outputTokens,
			       sum(e.costUsd) as costUsd,
			       sum(coalesce(e.linesAdded, 0)) as linesAdded,
			       sum(coalesce(e.linesRemoved, 0)) as linesRemoved
			from UsageEvent e
			where e.eventTime >= :from and e.eventTime < :to
			group by e.orgId, e.teamId, e.memberId, e.projectId
			""")
	List<UsageAggregate> aggregateBetween(@Param("from") Instant from, @Param("to") Instant to);

}
