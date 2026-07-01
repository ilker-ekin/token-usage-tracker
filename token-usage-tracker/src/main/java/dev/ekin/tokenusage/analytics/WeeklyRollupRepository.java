package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WeeklyRollupRepository extends JpaRepository<WeeklyRollup, UUID> {
}
