package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.AdvisorRecommendation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvisorRecommendationRepository extends JpaRepository<AdvisorRecommendation, Long> {
    Optional<AdvisorRecommendation> findFirstByCompany_IdOrderByCreatedAtDesc(Long companyId);
    Optional<AdvisorRecommendation> findByCompany_IdAndPeriodAndSource(Long companyId, String period, String source);
}

