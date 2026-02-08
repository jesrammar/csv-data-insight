package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Report;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    Optional<Report> findByCompanyIdAndPeriod(Long companyId, String period);
}
