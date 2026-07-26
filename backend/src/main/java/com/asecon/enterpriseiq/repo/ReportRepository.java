package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Report;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    List<Report> findByCompanyIdInAndPeriodInOrderByCompanyIdAscPeriodAscCreatedAtDesc(List<Long> companyIds, List<String> periods);
    List<Report> findByCompanyIdAndPeriodOrderByCreatedAtDesc(Long companyId, String period);
    Optional<Report> findTopByCompanyIdAndPeriodOrderByVersionNoDescCreatedAtDesc(Long companyId, String period);
    boolean existsByCompanyIdAndPeriod(Long companyId, String period);
}
