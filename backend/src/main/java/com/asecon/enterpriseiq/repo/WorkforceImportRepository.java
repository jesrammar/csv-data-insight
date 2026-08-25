package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.WorkforceImport;
import com.asecon.enterpriseiq.model.WorkforceImportKind;
import com.asecon.enterpriseiq.model.WorkforceImportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkforceImportRepository extends JpaRepository<WorkforceImport, Long> {
    Optional<WorkforceImport> findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(Long companyId, WorkforceImportKind importKind);
    Optional<WorkforceImport> findFirstByCompanyIdAndImportKindAndImportStatusOrderByCreatedAtDesc(
        Long companyId,
        WorkforceImportKind importKind,
        WorkforceImportStatus importStatus
    );
    Optional<WorkforceImport> findByIdAndCompanyIdAndImportKind(Long id, Long companyId, WorkforceImportKind importKind);
    List<WorkforceImport> findByCompanyIdAndImportKindOrderByCreatedAtDesc(Long companyId, WorkforceImportKind importKind);
    List<WorkforceImport> findTop12ByCompanyIdAndImportKindOrderByCreatedAtDesc(Long companyId, WorkforceImportKind importKind);
    void deleteByCompanyIdAndImportKind(Long companyId, WorkforceImportKind importKind);
}
