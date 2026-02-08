package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reports")
public class Report {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "storage_ref")
    private String storageRef;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public ReportFormat getFormat() { return format; }
    public void setFormat(ReportFormat format) { this.format = format; }
    public ReportStatus getStatus() { return status; }
    public void setStatus(ReportStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String storageRef) { this.storageRef = storageRef; }
}
