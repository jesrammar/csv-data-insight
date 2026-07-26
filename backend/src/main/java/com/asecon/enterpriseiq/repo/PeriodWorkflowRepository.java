package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.PeriodWorkflow;
import com.asecon.enterpriseiq.model.PeriodWorkflowStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodWorkflowRepository extends JpaRepository<PeriodWorkflow, Long> {
    Optional<PeriodWorkflow> findByCompanyIdAndPeriod(Long companyId, String period);

    List<PeriodWorkflow> findByCompanyIdInAndPeriodIn(List<Long> companyIds, List<String> periods);

    List<PeriodWorkflow> findByCompanyIdOrderByPeriodDesc(Long companyId);

    List<PeriodWorkflow> findTop25ByStatusInOrderByPriorityDescUpdatedAtAscIdAsc(List<PeriodWorkflowStatus> statuses);
}
