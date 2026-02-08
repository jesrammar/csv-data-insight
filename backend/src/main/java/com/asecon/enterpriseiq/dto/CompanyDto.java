package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.Plan;

public class CompanyDto {
    private Long id;
    private String name;
    private Plan plan;

    public CompanyDto() {}

    public CompanyDto(Long id, String name, Plan plan) {
        this.id = id;
        this.name = name;
        this.plan = plan;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Plan getPlan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
}
