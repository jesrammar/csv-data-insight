package com.asecon.enterpriseiq.dto;

import jakarta.validation.constraints.NotBlank;

public class ImportRequest {
    @NotBlank
    private String period;

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
}
