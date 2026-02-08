package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
}
