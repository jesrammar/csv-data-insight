package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.TribunalClient;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TribunalClientRepository extends JpaRepository<TribunalClient, Long> {
    List<TribunalClient> findByCompanyId(Long companyId);
    void deleteByCompanyId(Long companyId);
}
