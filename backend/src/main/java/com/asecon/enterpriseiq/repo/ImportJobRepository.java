package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    List<ImportJob> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<ImportJob> findByCompanyIdInAndPeriodInOrderByCompanyIdAscPeriodAscCreatedAtDesc(List<Long> companyIds, List<String> periods);

    Optional<ImportJob> findFirstByCompanyIdOrderByCreatedAtDesc(Long companyId);

    Optional<ImportJob> findFirstByCompanyIdAndPeriodOrderByVersionNoDescCreatedAtDesc(Long companyId, String period);

    Optional<ImportJob> findFirstByCompanyIdAndPeriodAndAppliedAtNotNullOrderByAppliedAtDesc(Long companyId, String period);

    Optional<ImportJob> findFirstByCompanyIdAndProcessedAtNotNullOrderByProcessedAtDesc(Long companyId);

    Optional<ImportJob> findFirstByCompanyIdAndStatusInOrderByRunAfterAscIdAsc(Long companyId, List<ImportStatus> status);

    List<ImportJob> findTop25ByStatusInAndRunAfterBeforeOrderByRunAfterAscIdAsc(List<ImportStatus> status, Instant now);

    @Query("""
        select j
          from ImportJob j
         where j.company.id = :companyId
           and j.period = :period
           and j.contentHash = :contentHash
           and j.id <> :excludeId
         order by j.createdAt desc
        """)
    List<ImportJob> findDuplicatesByContentHash(@Param("companyId") Long companyId,
                                                @Param("period") String period,
                                                @Param("contentHash") String contentHash,
                                                @Param("excludeId") Long excludeId);

    @Query("""
        select j
          from ImportJob j
         where j.company.id = :companyId
           and j.period = :period
           and j.normalizedHash = :normalizedHash
           and j.appliedAt is not null
           and j.id <> :excludeId
         order by j.appliedAt desc
        """)
    List<ImportJob> findAppliedDuplicatesByNormalizedHash(@Param("companyId") Long companyId,
                                                          @Param("period") String period,
                                                          @Param("normalizedHash") String normalizedHash,
                                                          @Param("excludeId") Long excludeId);

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
