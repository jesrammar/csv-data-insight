package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.AdvisorActionFollowUp;
import com.asecon.enterpriseiq.model.AdvisorActionFollowUpStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvisorActionFollowUpRepository extends JpaRepository<AdvisorActionFollowUp, Long> {
    List<AdvisorActionFollowUp> findByCompany_IdAndPeriodAndSourceOrderByCreatedAtAsc(Long companyId, String period, String source);
    List<AdvisorActionFollowUp> findByCompany_IdAndSourceAndPeriodLessThanAndStatusInOrderByPeriodDescUpdatedAtDesc(
        Long companyId,
        String source,
        String period,
        Collection<AdvisorActionFollowUpStatus> statuses
    );
    Optional<AdvisorActionFollowUp> findByIdAndCompany_Id(Long id, Long companyId);
}
