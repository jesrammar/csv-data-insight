package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "line_profits")
public class LineProfit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_line_id", nullable = false)
    private BusinessLine businessLine;

    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public BusinessLine getBusinessLine() { return businessLine; }
    public void setBusinessLine(BusinessLine businessLine) { this.businessLine = businessLine; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
