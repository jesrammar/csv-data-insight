package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.Role;

public class UpdateUserRequest {
    private Role role;
    private Boolean enabled;

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}

