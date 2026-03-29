package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "macro_observation",
       uniqueConstraints = @UniqueConstraint(name = "uq_macro_obs_series_period", columnNames = {"series_id", "period"}))
public class MacroObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private MacroSeries series;

    @Column(nullable = false, length = 16)
    private String period;

    @Column(precision = 20, scale = 10)
    private BigDecimal value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public MacroSeries getSeries() { return series; }
    public void setSeries(MacroSeries series) { this.series = series; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

