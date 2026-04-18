package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.CompanySavedMapping;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanySavedMappingRepository extends JpaRepository<CompanySavedMapping, Long> {
    Optional<CompanySavedMapping> findByCompany_IdAndMappingKey(Long companyId, String mappingKey);
}

