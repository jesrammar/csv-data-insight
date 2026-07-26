package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.User;
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

    public List<Company> findForUser(Long userId) {
        return companyRepository.findByUsers_Id(userId);
    }

    public List<Company> findVisibleForUser(User user) {
        if (user == null) return List.of();
        if (user.getRole() == Role.ADMIN) return findAll();
        return findForUser(user.getId());
    }

    public Company save(Company company) { return companyRepository.save(company); }

    public Company getById(Long id) { return companyRepository.findById(id).orElseThrow(); }

    public void delete(Long id) { companyRepository.deleteById(id); }
}
