package com.asecon.enterpriseiq.dto;

import java.util.List;

public class PortfolioOperationsDto {
    private List<String> months;
    private PortfolioOperationsSummaryDto summary;
    private List<PortfolioCompanyOperationDto> companies;

    public PortfolioOperationsDto() {}

    public PortfolioOperationsDto(List<String> months,
                                  PortfolioOperationsSummaryDto summary,
                                  List<PortfolioCompanyOperationDto> companies) {
        this.months = months;
        this.summary = summary;
        this.companies = companies;
    }

    public List<String> getMonths() { return months; }
    public void setMonths(List<String> months) { this.months = months; }
    public PortfolioOperationsSummaryDto getSummary() { return summary; }
    public void setSummary(PortfolioOperationsSummaryDto summary) { this.summary = summary; }
    public List<PortfolioCompanyOperationDto> getCompanies() { return companies; }
    public void setCompanies(List<PortfolioCompanyOperationDto> companies) { this.companies = companies; }
}
