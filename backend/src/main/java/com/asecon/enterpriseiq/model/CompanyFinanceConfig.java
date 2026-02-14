package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "company_finance_config")
public class CompanyFinanceConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, unique = true)
    private Company company;

    @Column(name = "fixed_costs", precision = 18, scale = 2)
    private BigDecimal fixedCosts;

    @Column(name = "variable_cost_ratio", precision = 5, scale = 4)
    private BigDecimal variableCostRatio;

    @Column(name = "discount_rate", precision = 5, scale = 4)
    private BigDecimal discountRate;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public BigDecimal getFixedCosts() { return fixedCosts; }
    public void setFixedCosts(BigDecimal fixedCosts) { this.fixedCosts = fixedCosts; }
    public BigDecimal getVariableCostRatio() { return variableCostRatio; }
    public void setVariableCostRatio(BigDecimal variableCostRatio) { this.variableCostRatio = variableCostRatio; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public void setDiscountRate(BigDecimal discountRate) { this.discountRate = discountRate; }
}
