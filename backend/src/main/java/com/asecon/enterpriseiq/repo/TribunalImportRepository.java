package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.TribunalImport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TribunalImportRepository extends JpaRepository<TribunalImport, Long> {
    List<TribunalImport> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    Optional<TribunalImport> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);
    boolean existsByCompanyId(Long companyId);
    void deleteByCompanyId(Long companyId);
}
