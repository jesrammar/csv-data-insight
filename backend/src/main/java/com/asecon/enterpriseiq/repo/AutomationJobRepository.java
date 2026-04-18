package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.AutomationJob;
import com.asecon.enterpriseiq.model.AutomationJobStatus;
import com.asecon.enterpriseiq.model.AutomationJobType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutomationJobRepository extends JpaRepository<AutomationJob, Long> {
    List<AutomationJob> findByCompany_IdOrderByCreatedAtDesc(Long companyId);

    List<AutomationJob> findTop25ByStatusInAndRunAfterBeforeOrderByRunAfterAscIdAsc(List<AutomationJobStatus> statuses, Instant now);

    @Modifying
    @Query("""
        update AutomationJob j
           set j.status = :toStatus,
               j.updatedAt = :now
         where j.id = :id
           and j.status in :fromStatuses
    """)
    int claim(@Param("id") Long id,
              @Param("fromStatuses") List<AutomationJobStatus> fromStatuses,
              @Param("toStatus") AutomationJobStatus toStatus,
              @Param("now") Instant now);

    boolean existsByCompany_IdAndTypeAndStatusIn(Long companyId, AutomationJobType type, List<AutomationJobStatus> statuses);

    boolean existsByCompany_IdAndTypeAndStatusInAndPayloadJsonContaining(Long companyId, AutomationJobType type, List<AutomationJobStatus> statuses, String payloadJsonPart);
}
