package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.Plan;
import java.util.List;

public class PortfolioCompanyOperationDto {
    private Long companyId;
    private String companyName;
    private Plan companyPlan;
    private List<PortfolioMonthFlowDto> items;
    private int blockedCount;
    private int warningCount;
    private int readyCount;
    private int pendingPdfCount;
    private int missingDataCount;
    private int priorityScore;
    private String statusLabel;

    public PortfolioCompanyOperationDto() {}

    public PortfolioCompanyOperationDto(Long companyId,
                                        String companyName,
                                        Plan companyPlan,
                                        List<PortfolioMonthFlowDto> items,
                                        int blockedCount,
                                        int warningCount,
                                        int readyCount,
                                        int pendingPdfCount,
                                        int missingDataCount,
                                        int priorityScore,
                                        String statusLabel) {
        this.companyId = companyId;
        this.companyName = companyName;
        this.companyPlan = companyPlan;
        this.items = items;
        this.blockedCount = blockedCount;
        this.warningCount = warningCount;
        this.readyCount = readyCount;
        this.pendingPdfCount = pendingPdfCount;
        this.missingDataCount = missingDataCount;
        this.priorityScore = priorityScore;
        this.statusLabel = statusLabel;
    }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Plan getCompanyPlan() { return companyPlan; }
    public void setCompanyPlan(Plan companyPlan) { this.companyPlan = companyPlan; }
    public List<PortfolioMonthFlowDto> getItems() { return items; }
    public void setItems(List<PortfolioMonthFlowDto> items) { this.items = items; }
    public int getBlockedCount() { return blockedCount; }
    public void setBlockedCount(int blockedCount) { this.blockedCount = blockedCount; }
    public int getWarningCount() { return warningCount; }
    public void setWarningCount(int warningCount) { this.warningCount = warningCount; }
    public int getReadyCount() { return readyCount; }
    public void setReadyCount(int readyCount) { this.readyCount = readyCount; }
    public int getPendingPdfCount() { return pendingPdfCount; }
    public void setPendingPdfCount(int pendingPdfCount) { this.pendingPdfCount = pendingPdfCount; }
    public int getMissingDataCount() { return missingDataCount; }
    public void setMissingDataCount(int missingDataCount) { this.missingDataCount = missingDataCount; }
    public int getPriorityScore() { return priorityScore; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }
    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }
}
