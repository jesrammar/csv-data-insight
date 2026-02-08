package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.CompanyDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.CompanyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {
    private final CompanyService companyService;
    private final AccessService accessService;

    public CompanyController(CompanyService companyService, AccessService accessService) {
        this.companyService = companyService;
        this.accessService = accessService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<CompanyDto> list() {
        return companyService.findAll().stream()
            .map(c -> new CompanyDto(c.getId(), c.getName(), c.getPlan()))
            .collect(Collectors.toList());
    }

    @GetMapping("/mine")
    public List<CompanyDto> myCompanies() {
        var user = accessService.currentUser();
        if (user == null) return List.of();
        if (user.getRole() == Role.ADMIN) {
            return list();
        }
        return user.getCompanies().stream()
            .map(c -> new CompanyDto(c.getId(), c.getName(), c.getPlan()))
            .collect(Collectors.toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyDto create(@Valid @RequestBody CompanyDto request) {
        Company company = new Company();
        company.setName(request.getName());
        company.setPlan(request.getPlan());
        company = companyService.save(company);
        return new CompanyDto(company.getId(), company.getName(), company.getPlan());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyDto update(@PathVariable Long id, @Valid @RequestBody CompanyDto request) {
        Company company = companyService.getById(id);
        company.setName(request.getName());
        company.setPlan(request.getPlan());
        company = companyService.save(company);
        return new CompanyDto(company.getId(), company.getName(), company.getPlan());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        companyService.delete(id);
    }
}
