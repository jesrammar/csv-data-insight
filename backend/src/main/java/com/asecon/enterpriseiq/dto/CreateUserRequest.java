package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public class CreateUserRequest {
    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String password;

    @NotNull
    private Role role;

    private Set<Long> companyIds;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Set<Long> getCompanyIds() { return companyIds; }
    public void setCompanyIds(Set<Long> companyIds) { this.companyIds = companyIds; }
}
