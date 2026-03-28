package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Company;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    List<Company> findByUsers_Id(Long userId);
    boolean existsByIdAndUsers_Id(Long id, Long userId);
}
