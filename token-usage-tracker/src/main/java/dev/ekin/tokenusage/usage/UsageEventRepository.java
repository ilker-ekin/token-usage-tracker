package dev.ekin.tokenusage.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsageEventRepository
		extends JpaRepository<UsageEvent, UUID>, JpaSpecificationExecutor<UsageEvent> {

	Optional<UsageEvent> findByOrgIdAndIdempotencyKey(UUID orgId, String idempotencyKey);

	/**
	 * Inserts an event that carries an idempotency key, or does nothing when
	 * the org already has an event with that key. Returns the number of rows
	 * inserted (1 or 0).
	 *
	 * <p>A native statement because a JPA save that hits the unique index
	 * aborts the whole PostgreSQL transaction, and the caller still needs it
	 * to read the existing event. A concurrent insert of the same key waits
	 * on the index until the first transaction commits, then inserts nothing.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			insert into usage_event (id, org_id, team_id, member_id, project_id, model,
			        input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
			        lines_added, lines_removed, cost_usd, event_time, idempotency_key)
			values (:id, :orgId, cast(:teamId as uuid), :memberId, :projectId, :model,
			        :inputTokens, :outputTokens, :cacheReadTokens, :cacheCreationTokens,
			        cast(:linesAdded as integer), cast(:linesRemoved as integer),
			        :costUsd, :eventTime, :idempotencyKey)
			on conflict (org_id, idempotency_key) where idempotency_key is not null do nothing
			""")
	int insertIfKeyAbsent(@Param("id") UUID id, @Param("orgId") UUID orgId,
			@Param("teamId") UUID teamId, @Param("memberId") UUID memberId,
			@Param("projectId") UUID projectId, @Param("model") String model,
			@Param("inputTokens") int inputTokens, @Param("outputTokens") int outputTokens,
			@Param("cacheReadTokens") int cacheReadTokens,
			@Param("cacheCreationTokens") int cacheCreationTokens,
			@Param("linesAdded") Integer linesAdded, @Param("linesRemoved") Integer linesRemoved,
			@Param("costUsd") BigDecimal costUsd, @Param("eventTime") Instant eventTime,
			@Param("idempotencyKey") String idempotencyKey);

	interface UsageAggregate {
		UUID getOrgId();
		UUID getTeamId();
		UUID getMemberId();
		UUID getProjectId();
		long getInputTokens();
		long getOutputTokens();
		long getCacheReadTokens();
		long getCacheCreationTokens();
		BigDecimal getCostUsd();
		long getLinesAdded();
		long getLinesRemoved();
	}

	@Query("""
			select e.orgId as orgId, e.teamId as teamId, e.memberId as memberId, e.projectId as projectId,
			       sum(e.inputTokens) as inputTokens, sum(e.outputTokens) as outputTokens,
			       sum(e.cacheReadTokens) as cacheReadTokens,
			       sum(e.cacheCreationTokens) as cacheCreationTokens,
			       sum(e.costUsd) as costUsd,
			       sum(coalesce(e.linesAdded, 0)) as linesAdded,
			       sum(coalesce(e.linesRemoved, 0)) as linesRemoved
			from UsageEvent e
			where e.eventTime >= :from and e.eventTime < :to
			group by e.orgId, e.teamId, e.memberId, e.projectId
			""")
	List<UsageAggregate> aggregateBetween(@Param("from") Instant from, @Param("to") Instant to);

}
