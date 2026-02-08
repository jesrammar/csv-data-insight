package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.security.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AccessService {
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
        return user.getCompanies().stream().anyMatch(c -> c.getId().equals(companyId));
    }

    public void requireCompanyAccess(User user, Long companyId) {
        if (!canAccessCompany(user, companyId)) {
            throw new IllegalArgumentException("Access denied to company " + companyId);
        }
    }
}
