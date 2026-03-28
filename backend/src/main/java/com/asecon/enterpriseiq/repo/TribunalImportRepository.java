package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.TribunalImport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TribunalImportRepository extends JpaRepository<TribunalImport, Long> {
    List<TribunalImport> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    void deleteByCompanyId(Long companyId);
}
