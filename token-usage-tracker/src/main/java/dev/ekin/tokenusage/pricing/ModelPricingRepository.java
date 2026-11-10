package dev.ekin.tokenusage.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, UUID> {

	/**
	 * Pricing rows valid at the given instant, most recent first — overlapping
	 * rows are resolved in favor of the latest valid_from.
	 */
	@Query("""
			select p from ModelPricing p
			where p.model = :model
			  and p.validFrom <= :at
			  and (p.validTo is null or p.validTo > :at)
			order by p.validFrom desc
			""")
	List<ModelPricing> findActiveAt(@Param("model") String model, @Param("at") Instant at);

	/**
	 * Serializes writers of one model's pricing until the surrounding
	 * transaction ends, so two requests that both miss do not both insert.
	 * The lock function returns void; the cast makes it a value JDBC can read.
	 */
	@Query(nativeQuery = true,
			value = "select cast(pg_advisory_xact_lock(hashtext(:model)) as text)")
	String lockModel(@Param("model") String model);

}
