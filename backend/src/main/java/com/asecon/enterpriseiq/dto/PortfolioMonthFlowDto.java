package com.asecon.enterpriseiq.dto;

public class PortfolioMonthFlowDto {
    private String period;
    private String importStatus;
    private boolean hasReading;
    private boolean hasReport;
    private PortfolioWorkflowStepDto portfolioStep;

    public PortfolioMonthFlowDto() {}

    public PortfolioMonthFlowDto(String period, String importStatus, boolean hasReading, boolean hasReport, PortfolioWorkflowStepDto portfolioStep) {
        this.period = period;
        this.importStatus = importStatus;
        this.hasReading = hasReading;
        this.hasReport = hasReport;
        this.portfolioStep = portfolioStep;
    }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getImportStatus() { return importStatus; }
    public void setImportStatus(String importStatus) { this.importStatus = importStatus; }
    public boolean isHasReading() { return hasReading; }
    public void setHasReading(boolean hasReading) { this.hasReading = hasReading; }
    public boolean isHasReport() { return hasReport; }
    public void setHasReport(boolean hasReport) { this.hasReport = hasReport; }
    public PortfolioWorkflowStepDto getPortfolioStep() { return portfolioStep; }
    public void setPortfolioStep(PortfolioWorkflowStepDto portfolioStep) { this.portfolioStep = portfolioStep; }
}
