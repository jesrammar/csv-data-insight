package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "forecasts")
public class ForecastEntry {
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ForecastSource source;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BudgetMetric getMetric() { return metric; }
    public void setMetric(BudgetMetric metric) { this.metric = metric; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public ForecastSource getSource() { return source; }
    public void setSource(ForecastSource source) { this.source = source; }
}
