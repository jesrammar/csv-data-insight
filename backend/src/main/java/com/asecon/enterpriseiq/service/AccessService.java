package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.security.SecurityUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessService {
    private final CompanyRepository companyRepository;

    public AccessService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SecurityUser)) {
            return null;
        }
        return ((SecurityUser) auth.getPrincipal()).getUser();
    }

    public boolean canAccessCompany(User user, Long companyId) {
        if (user == null) return false;
        if (user.getRole() == Role.ADMIN) return true;
        if (companyId == null || user.getId() == null) return false;
        return companyRepository.existsByIdAndUsers_Id(companyId, user.getId());
    }

    public void requireCompanyAccess(User user, Long companyId) {
        if (!canAccessCompany(user, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to company " + companyId);
        }
    }

    public void requirePlanAtLeast(Long companyId, Plan minimum) {
        Company company = companyRepository.findById(companyId).orElseThrow();
        if (!company.getPlan().isAtLeast(minimum)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Plan does not allow this feature");
        }
    }

    public Company requireCompany(Long companyId) {
        return companyRepository.findById(companyId).orElseThrow();
    }
}
