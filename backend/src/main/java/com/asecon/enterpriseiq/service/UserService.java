package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.CreateUserRequest;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, CompanyRepository companyRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> findAll() { return userRepository.findAll(); }

    public User create(CreateUserRequest request) {
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setEnabled(true);

        if (request.getCompanyIds() != null && !request.getCompanyIds().isEmpty()) {
            Set<Company> companies = new HashSet<>(companyRepository.findAllById(request.getCompanyIds()));
            user.setCompanies(companies);
        }

        return userRepository.save(user);
    }

    public User updateCompanies(Long userId, Set<Long> companyIds) {
        User user = userRepository.findById(userId).orElseThrow();
        Set<Company> companies = new HashSet<>(companyRepository.findAllById(companyIds));
        user.setCompanies(companies);
        return userRepository.save(user);
    }
}
