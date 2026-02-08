package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Transaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByCompanyIdAndPeriodBetween(Long companyId, String from, String to);
    List<Transaction> findByCompanyIdAndPeriod(Long companyId, String period);
    void deleteByCompanyIdAndPeriod(Long companyId, String period);
}
