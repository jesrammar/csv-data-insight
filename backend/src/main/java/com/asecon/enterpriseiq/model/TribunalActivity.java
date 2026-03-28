package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;

@Entity
@Table(name = "tribunal_activity")
public class TribunalActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "client_id")
    private TribunalClient client;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "n_as", nullable = false)
    private Integer nAs;

    public Long getId() { return id; }
    public TribunalClient getClient() { return client; }
    public void setClient(TribunalClient client) { this.client = client; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public Integer getNAs() { return nAs; }
    public void setNAs(Integer nAs) { this.nAs = nAs; }
}
