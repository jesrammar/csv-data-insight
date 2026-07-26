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
    name = "advisor_action_followups",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_advisor_action_followups_company_period_source_key",
        columnNames = {"company_id", "period", "source", "action_key"}
    )
)
public class AdvisorActionFollowUp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_snapshot_id")
    private AdvisorRecommendation recommendationSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_follow_up_id")
    private AdvisorActionFollowUp originFollowUp;

    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "action_index")
    private Integer actionIndex;

    @Column(name = "action_key", nullable = false, length = 64)
    private String actionKey;

    @Column(length = 32)
    private String horizon;

    @Column(length = 32)
    private String priority;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 255)
    private String kpi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdvisorActionFollowUpStatus status;

    @Column(name = "carried_over", nullable = false)
    private boolean carriedOver;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public AdvisorRecommendation getRecommendationSnapshot() { return recommendationSnapshot; }
    public void setRecommendationSnapshot(AdvisorRecommendation recommendationSnapshot) { this.recommendationSnapshot = recommendationSnapshot; }
    public AdvisorActionFollowUp getOriginFollowUp() { return originFollowUp; }
    public void setOriginFollowUp(AdvisorActionFollowUp originFollowUp) { this.originFollowUp = originFollowUp; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getActionIndex() { return actionIndex; }
    public void setActionIndex(Integer actionIndex) { this.actionIndex = actionIndex; }
    public String getActionKey() { return actionKey; }
    public void setActionKey(String actionKey) { this.actionKey = actionKey; }
    public String getHorizon() { return horizon; }
    public void setHorizon(String horizon) { this.horizon = horizon; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getKpi() { return kpi; }
    public void setKpi(String kpi) { this.kpi = kpi; }
    public AdvisorActionFollowUpStatus getStatus() { return status; }
    public void setStatus(AdvisorActionFollowUpStatus status) { this.status = status; }
    public boolean isCarriedOver() { return carriedOver; }
    public void setCarriedOver(boolean carriedOver) { this.carriedOver = carriedOver; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
