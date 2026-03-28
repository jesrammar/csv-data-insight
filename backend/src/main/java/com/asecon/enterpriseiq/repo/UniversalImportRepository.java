package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.UniversalImport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UniversalImportRepository extends JpaRepository<UniversalImport, Long> {
    Optional<UniversalImport> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);
}
