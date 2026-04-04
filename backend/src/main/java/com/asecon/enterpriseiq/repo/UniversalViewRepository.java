package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.UniversalView;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UniversalViewRepository extends JpaRepository<UniversalView, Long> {
    List<UniversalView> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    Optional<UniversalView> findByIdAndCompanyId(Long id, Long companyId);
    boolean existsByCompanyIdAndSourceUniversalImportId(Long companyId, Long sourceUniversalImportId);
}
