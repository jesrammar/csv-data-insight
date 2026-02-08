package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.Role;
import java.util.Set;

public class UserDto {
    private Long id;
    private String email;
    private Role role;
    private boolean enabled;
    private Set<Long> companyIds;

    public UserDto() {}

    public UserDto(Long id, String email, Role role, boolean enabled, Set<Long> companyIds) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.enabled = enabled;
        this.companyIds = companyIds;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<Long> getCompanyIds() { return companyIds; }
    public void setCompanyIds(Set<Long> companyIds) { this.companyIds = companyIds; }
}
