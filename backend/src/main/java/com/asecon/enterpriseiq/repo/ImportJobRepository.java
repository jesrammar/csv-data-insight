package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    List<ImportJob> findByStatusOrderByCreatedAtAsc(ImportStatus status);
    List<ImportJob> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
}
