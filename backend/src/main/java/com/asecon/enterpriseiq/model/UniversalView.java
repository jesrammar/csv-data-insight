package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "universal_views")
public class UniversalView {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "source_universal_import_id")
    private Long sourceUniversalImportId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public Long getSourceUniversalImportId() { return sourceUniversalImportId; }
    public void setSourceUniversalImportId(Long sourceUniversalImportId) { this.sourceUniversalImportId = sourceUniversalImportId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
