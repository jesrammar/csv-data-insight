package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReportServiceVersioningTest {
    private static final AtomicLong COMPANY_IDS = new AtomicLong(40_000);

    @Autowired
    private ReportService reportService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generateHtmlReport_createsNewVersionForSamePeriod() throws Exception {
        Company company = createCompany("Report versioning");

        Report first = reportService.generateHtmlReport(company, "2026-11", "<html><body>v1</body></html>");
        Report second = reportService.generateHtmlReport(company, "2026-11", "<html><body>v2</body></html>");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getVersionNo()).isEqualTo(1);
        assertThat(second.getVersionNo()).isEqualTo(2);
        assertThat(second.getStorageRef()).isNotEqualTo(first.getStorageRef());

        var stored = reportRepository.findByCompanyIdAndPeriodOrderByCreatedAtDesc(company.getId(), "2026-11");
        assertThat(stored).hasSize(2);
        assertThat(reportRepository.findTopByCompanyIdAndPeriodOrderByVersionNoDescCreatedAtDesc(company.getId(), "2026-11"))
            .get()
            .extracting(Report::getVersionNo)
            .isEqualTo(2);
    }

    @Test
    void generateHtmlReport_persistsSelectedUniversalViewMeta() throws Exception {
        Company company = createCompany("Report universal selection");

        Report report = reportService.generateHtmlReport(
            company,
            "2026-12",
            "<html><body>v1</body></html>",
            77L,
            "Facturas por cliente",
            "DISTINCT_INVOICE_COUNT"
        );

        Report stored = reportRepository.findById(report.getId()).orElseThrow();
        assertThat(stored.getSelectedUniversalViewId()).isEqualTo(77L);
        assertThat(stored.getSelectedUniversalViewName()).isEqualTo("Facturas por cliente");
        assertThat(stored.getSelectedUniversalAggregationMode()).isEqualTo("DISTINCT_INVOICE_COUNT");
    }

    private Company createCompany(String suffix) {
        long id = COMPANY_IDS.incrementAndGet();
        jdbcTemplate.update(
            "insert into companies (id, name, plan) values (?, ?, ?)",
            id,
            "Test " + suffix,
            Plan.GOLD.name()
        );
        return companyRepository.findById(id).orElseThrow();
    }
}
