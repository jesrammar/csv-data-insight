package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "alert_rules")
public class AlertRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "net_flow_min_threshold", nullable = false, precision = 18, scale = 2)
    private BigDecimal netFlowMinThreshold;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public BigDecimal getNetFlowMinThreshold() { return netFlowMinThreshold; }
    public void setNetFlowMinThreshold(BigDecimal netFlowMinThreshold) { this.netFlowMinThreshold = netFlowMinThreshold; }
}
