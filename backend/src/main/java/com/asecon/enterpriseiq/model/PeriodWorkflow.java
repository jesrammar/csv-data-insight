package com.asecon.enterpriseiq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "period_workflows",
    uniqueConstraints = @UniqueConstraint(name = "uq_period_workflows_company_period", columnNames = {"company_id", "period"})
)
public class PeriodWorkflow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PeriodWorkflowStatus status = PeriodWorkflowStatus.PENDING_DATA;

    @Column(nullable = false)
    private Integer priority = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_import_id")
    private ImportJob sourceImport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_snapshot_id")
    private AdvisorRecommendation recommendationSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_import_id")
    private TribunalImport portfolioImport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "blocking_code", length = 64)
    private String blockingCode;

    @Column(name = "blocking_reason", columnDefinition = "TEXT")
    private String blockingReason;

    @Column(name = "exception_count", nullable = false)
    private Integer exceptionCount = 0;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public PeriodWorkflowStatus getStatus() { return status; }
    public void setStatus(PeriodWorkflowStatus status) { this.status = status; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public ImportJob getSourceImport() { return sourceImport; }
    public void setSourceImport(ImportJob sourceImport) { this.sourceImport = sourceImport; }
    public Report getReport() { return report; }
    public void setReport(Report report) { this.report = report; }
    public AdvisorRecommendation getRecommendationSnapshot() { return recommendationSnapshot; }
    public void setRecommendationSnapshot(AdvisorRecommendation recommendationSnapshot) { this.recommendationSnapshot = recommendationSnapshot; }
    public TribunalImport getPortfolioImport() { return portfolioImport; }
    public void setPortfolioImport(TribunalImport portfolioImport) { this.portfolioImport = portfolioImport; }
    public User getOwnerUser() { return ownerUser; }
    public void setOwnerUser(User ownerUser) { this.ownerUser = ownerUser; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public String getBlockingCode() { return blockingCode; }
    public void setBlockingCode(String blockingCode) { this.blockingCode = blockingCode; }
    public String getBlockingReason() { return blockingReason; }
    public void setBlockingReason(String blockingReason) { this.blockingReason = blockingReason; }
    public Integer getExceptionCount() { return exceptionCount; }
    public void setExceptionCount(Integer exceptionCount) { this.exceptionCount = exceptionCount; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
