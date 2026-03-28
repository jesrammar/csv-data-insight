package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Transaction;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {
    List<Transaction> findByCompanyIdAndPeriodBetween(Long companyId, String from, String to);
    List<Transaction> findByCompanyIdAndPeriod(Long companyId, String period);

    List<Transaction> findByCompanyIdAndPeriodOrderByTxnDateAsc(Long companyId, String period);
    List<Transaction> findByCompanyIdAndPeriodBetweenOrderByPeriodAscTxnDateAsc(Long companyId, String from, String to);

    Page<Transaction> findByCompanyIdAndPeriod(Long companyId, String period, Pageable pageable);
    Page<Transaction> findByCompanyIdAndPeriodBetween(Long companyId, String from, String to, Pageable pageable);
    Page<Transaction> findByCompanyIdAndPeriodOrderByTxnDateAsc(Long companyId, String period, Pageable pageable);
    Page<Transaction> findByCompanyIdAndPeriodBetweenOrderByPeriodAscTxnDateAsc(Long companyId, String from, String to, Pageable pageable);

    void deleteByCompanyIdAndPeriod(Long companyId, String period);

    @Query("select distinct t.period from Transaction t where t.company.id = :companyId order by t.period desc")
    List<String> findDistinctPeriodsDesc(@Param("companyId") Long companyId);
}
