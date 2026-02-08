package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.AlertRule;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    Optional<AlertRule> findByCompanyId(Long companyId);
}
