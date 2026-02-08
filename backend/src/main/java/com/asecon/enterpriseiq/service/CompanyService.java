package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CompanyService {
    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public List<Company> findAll() { return companyRepository.findAll(); }

    public Company save(Company company) { return companyRepository.save(company); }

    public Company getById(Long id) { return companyRepository.findById(id).orElseThrow(); }

    public void delete(Long id) { companyRepository.deleteById(id); }
}
