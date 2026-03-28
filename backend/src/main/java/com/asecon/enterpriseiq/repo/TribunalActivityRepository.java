package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.TribunalActivity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TribunalActivityRepository extends JpaRepository<TribunalActivity, Long> {
    List<TribunalActivity> findByCompanyId(Long companyId);
    void deleteByCompanyId(Long companyId);
}
