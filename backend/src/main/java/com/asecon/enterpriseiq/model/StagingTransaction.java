package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "staging_transactions")
public class StagingTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "import_id")
    private ImportJob importJob;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    private String counterparty;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    public Long getId() { return id; }
    public ImportJob getImportJob() { return importJob; }
    public void setImportJob(ImportJob importJob) { this.importJob = importJob; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public LocalDate getTxnDate() { return txnDate; }
    public void setTxnDate(LocalDate txnDate) { this.txnDate = txnDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCounterparty() { return counterparty; }
    public void setCounterparty(String counterparty) { this.counterparty = counterparty; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
}
