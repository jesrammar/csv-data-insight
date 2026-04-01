package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByCompanyIdOrderByAtDesc(Long companyId, Pageable pageable);
}

