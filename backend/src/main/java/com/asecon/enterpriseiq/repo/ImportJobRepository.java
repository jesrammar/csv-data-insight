package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    List<ImportJob> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<ImportJob> findTop25ByStatusInAndRunAfterBeforeOrderByRunAfterAscIdAsc(List<ImportStatus> status, Instant now);

    @Modifying
    @Query("""
        update ImportJob j
           set j.status = :toStatus,
               j.updatedAt = :now
         where j.id = :id
           and j.status in :fromStatuses
        """)
    int claim(@Param("id") Long id,
              @Param("fromStatuses") List<ImportStatus> fromStatuses,
              @Param("toStatus") ImportStatus toStatus,
              @Param("now") Instant now);

    @Modifying
    @Query("""
        update ImportJob j
           set j.status = :toStatus,
               j.runAfter = :runAfter,
               j.attempts = :attempts,
               j.updatedAt = :now,
               j.lastError = :lastError
         where j.id = :id
        """)
    int updateScheduling(@Param("id") Long id,
                         @Param("toStatus") ImportStatus toStatus,
                         @Param("runAfter") Instant runAfter,
                         @Param("attempts") int attempts,
                         @Param("now") Instant now,
                         @Param("lastError") String lastError);
}
