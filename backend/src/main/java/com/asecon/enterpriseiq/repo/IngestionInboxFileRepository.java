package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.IngestionFileStatus;
import com.asecon.enterpriseiq.model.IngestionInboxFile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionInboxFileRepository extends JpaRepository<IngestionInboxFile, Long> {
    List<IngestionInboxFile> findTop200ByCompanyIdOrderByDetectedAtDesc(Long companyId);

    long countByCompanyId(Long companyId);

    long countByCompanyIdAndStatus(Long companyId, IngestionFileStatus status);

    Optional<IngestionInboxFile> findFirstByCompanyIdOrderByDetectedAtDesc(Long companyId);
}
