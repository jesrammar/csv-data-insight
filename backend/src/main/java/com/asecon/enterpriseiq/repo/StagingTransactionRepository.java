package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.StagingTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagingTransactionRepository extends JpaRepository<StagingTransaction, Long> {
    List<StagingTransaction> findByImportJobId(Long importId);
    void deleteByImportJobId(Long importId);
}
