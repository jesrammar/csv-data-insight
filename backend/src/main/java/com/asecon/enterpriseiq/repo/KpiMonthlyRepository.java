package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.KpiMonthly;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KpiMonthlyRepository extends JpaRepository<KpiMonthly, Long> {
    List<KpiMonthly> findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(Long companyId, String from, String to);
    Optional<KpiMonthly> findByCompanyIdAndPeriod(Long companyId, String period);
    void deleteByCompanyIdAndPeriod(Long companyId, String period);
}
