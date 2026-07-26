package com.asecon.enterpriseiq.dto;

public class PortfolioOperationsSummaryDto {
    private int activeCompanies;
    private int blockedCompanies;
    private int readyCompanies;
    private Long topPriorityCompanyId;
    private String topPriorityCompanyName;
    private Integer topPriorityBlockedCount;
    private Integer topPriorityPendingPdfCount;

    public PortfolioOperationsSummaryDto() {}

    public PortfolioOperationsSummaryDto(int activeCompanies,
                                         int blockedCompanies,
                                         int readyCompanies,
                                         Long topPriorityCompanyId,
                                         String topPriorityCompanyName,
                                         Integer topPriorityBlockedCount,
                                         Integer topPriorityPendingPdfCount) {
        this.activeCompanies = activeCompanies;
        this.blockedCompanies = blockedCompanies;
        this.readyCompanies = readyCompanies;
        this.topPriorityCompanyId = topPriorityCompanyId;
        this.topPriorityCompanyName = topPriorityCompanyName;
        this.topPriorityBlockedCount = topPriorityBlockedCount;
        this.topPriorityPendingPdfCount = topPriorityPendingPdfCount;
    }

    public int getActiveCompanies() { return activeCompanies; }
    public void setActiveCompanies(int activeCompanies) { this.activeCompanies = activeCompanies; }
    public int getBlockedCompanies() { return blockedCompanies; }
    public void setBlockedCompanies(int blockedCompanies) { this.blockedCompanies = blockedCompanies; }
    public int getReadyCompanies() { return readyCompanies; }
    public void setReadyCompanies(int readyCompanies) { this.readyCompanies = readyCompanies; }
    public Long getTopPriorityCompanyId() { return topPriorityCompanyId; }
    public void setTopPriorityCompanyId(Long topPriorityCompanyId) { this.topPriorityCompanyId = topPriorityCompanyId; }
    public String getTopPriorityCompanyName() { return topPriorityCompanyName; }
    public void setTopPriorityCompanyName(String topPriorityCompanyName) { this.topPriorityCompanyName = topPriorityCompanyName; }
    public Integer getTopPriorityBlockedCount() { return topPriorityBlockedCount; }
    public void setTopPriorityBlockedCount(Integer topPriorityBlockedCount) { this.topPriorityBlockedCount = topPriorityBlockedCount; }
    public Integer getTopPriorityPendingPdfCount() { return topPriorityPendingPdfCount; }
    public void setTopPriorityPendingPdfCount(Integer topPriorityPendingPdfCount) { this.topPriorityPendingPdfCount = topPriorityPendingPdfCount; }
}
