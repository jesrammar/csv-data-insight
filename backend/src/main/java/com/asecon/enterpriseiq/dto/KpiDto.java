package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public class KpiDto {
    private String period;
    private BigDecimal inflows;
    private BigDecimal outflows;
    private BigDecimal netFlow;
    private BigDecimal endingBalance;

    public KpiDto() {}

    public KpiDto(String period, BigDecimal inflows, BigDecimal outflows, BigDecimal netFlow, BigDecimal endingBalance) {
        this.period = period;
        this.inflows = inflows;
        this.outflows = outflows;
        this.netFlow = netFlow;
        this.endingBalance = endingBalance;
    }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getInflows() { return inflows; }
    public void setInflows(BigDecimal inflows) { this.inflows = inflows; }
    public BigDecimal getOutflows() { return outflows; }
    public void setOutflows(BigDecimal outflows) { this.outflows = outflows; }
    public BigDecimal getNetFlow() { return netFlow; }
    public void setNetFlow(BigDecimal netFlow) { this.netFlow = netFlow; }
    public BigDecimal getEndingBalance() { return endingBalance; }
    public void setEndingBalance(BigDecimal endingBalance) { this.endingBalance = endingBalance; }
}
