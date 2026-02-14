package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "budgets")
public class BudgetEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false)
    private BudgetMetric metric;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private CostCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_line_id")
    private BusinessLine businessLine;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BudgetMetric getMetric() { return metric; }
    public void setMetric(BudgetMetric metric) { this.metric = metric; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public CostCategory getCategory() { return category; }
    public void setCategory(CostCategory category) { this.category = category; }
    public BusinessLine getBusinessLine() { return businessLine; }
    public void setBusinessLine(BusinessLine businessLine) { this.businessLine = businessLine; }
}
