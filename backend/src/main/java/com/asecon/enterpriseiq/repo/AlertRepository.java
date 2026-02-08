package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Alert;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByCompanyIdAndPeriod(Long companyId, String period);
}
