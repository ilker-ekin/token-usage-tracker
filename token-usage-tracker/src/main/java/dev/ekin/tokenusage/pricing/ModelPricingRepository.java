package dev.ekin.tokenusage.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, UUID> {
}
