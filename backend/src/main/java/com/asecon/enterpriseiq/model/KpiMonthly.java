package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "kpi_monthly")
public class KpiMonthly {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String period;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal inflows;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal outflows;

    @Column(name = "net_flow", nullable = false, precision = 18, scale = 2)
    private BigDecimal netFlow;

    @Column(name = "ending_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal endingBalance;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
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
