package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ImportServiceHardeningTest {
    private static final AtomicLong COMPANY_IDS = new AtomicLong(10_000);

    @Autowired
    private ImportService importService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createImport_blocksExactDuplicateByContentHash() throws Exception {
        Company company = createCompany("Exact duplicate");
        MockMultipartFile file = csv(
            "enero.csv",
            """
            txn_date,amount,description,counterparty
            2026-01-01,100.00,Cobro,Cliente A
            """
        );

        ImportJob first = importService.createImport(company.getId(), "2026-01", file);
        ImportJob second = importService.createImport(company.getId(), "2026-01", file);

        ImportJob reloadedSecond = importJobRepository.findById(second.getId()).orElseThrow();
        assertThat(first.getVersionNo()).isEqualTo(1);
        assertThat(reloadedSecond.getVersionNo()).isEqualTo(2);
        assertThat(reloadedSecond.getStatus()).isEqualTo(ImportStatus.BLOCKED);
        assertThat(reloadedSecond.getDuplicateOfImportId()).isEqualTo(first.getId());
        assertThat(reloadedSecond.getBlockingCode()).isEqualTo("DUPLICATE_CONTENT_HASH");
    }

    @Test
    void processImport_blocksRowsOutsideDeclaredPeriod() throws Exception {
        Company company = createCompany("Outside period");
        MockMultipartFile file = csv(
            "mezclado.csv",
            """
            txn_date,amount,description,counterparty
            2026-02-01,10.00,Movimiento,Cliente B
            """
        );

        ImportJob created = importService.createImport(company.getId(), "2026-01", file);
        importService.processImport(created.getId());

        ImportJob blocked = importJobRepository.findById(created.getId()).orElseThrow();
        assertThat(blocked.getStatus()).isEqualTo(ImportStatus.BLOCKED);
        assertThat(blocked.getBlockingCode()).isEqualTo("OUTSIDE_PERIOD_ROWS");
        assertThat(transactionRepository.findByCompanyIdAndPeriod(company.getId(), "2026-01")).isEmpty();
    }

    @Test
    void processImport_blocksDuplicateEffectiveContentEvenWithDifferentRowOrder() throws Exception {
        Company company = createCompany("Normalized duplicate");

        ImportJob first = importService.createImport(company.getId(), "2026-01", csv(
            "v1.csv",
            """
            txn_date,amount,description,counterparty
            2026-01-01,100.00,Cobro,Cliente A
            2026-01-02,-40.00,Pago,Proveedor A
            """
        ));
        importService.processImport(first.getId());

        ImportJob second = importService.createImport(company.getId(), "2026-01", csv(
            "v2.csv",
            """
            txn_date,amount,description,counterparty
            2026-01-02,-40.00,Pago,Proveedor A
            2026-01-01,100.00,Cobro,Cliente A
            """
        ));
        importService.processImport(second.getId());

        ImportJob applied = importJobRepository.findById(first.getId()).orElseThrow();
        ImportJob blocked = importJobRepository.findById(second.getId()).orElseThrow();

        assertThat(applied.getStatus()).isEqualTo(ImportStatus.OK);
        assertThat(applied.getAppliedAt()).isNotNull();
        assertThat(blocked.getStatus()).isEqualTo(ImportStatus.BLOCKED);
        assertThat(blocked.getBlockingCode()).isEqualTo("DUPLICATE_NORMALIZED_HASH");
        assertThat(blocked.getDuplicateOfImportId()).isEqualTo(first.getId());
        assertThat(transactionRepository.findByCompanyIdAndPeriod(company.getId(), "2026-01")).hasSize(2);
    }

    @Test
    void processImport_linksSuccessfulReplacementToPreviousAppliedVersion() throws Exception {
        Company company = createCompany("Replacement");

        ImportJob first = importService.createImport(company.getId(), "2026-01", csv(
            "base.csv",
            """
            txn_date,amount,description,counterparty
            2026-01-01,100.00,Cobro,Cliente A
            """
        ));
        importService.processImport(first.getId());

        ImportJob second = importService.createImport(company.getId(), "2026-01", csv(
            "nuevo.csv",
            """
            txn_date,amount,description,counterparty
            2026-01-01,120.00,Cobro actualizado,Cliente A
            """
        ));
        importService.processImport(second.getId());

        ImportJob replaced = importJobRepository.findById(second.getId()).orElseThrow();
        assertThat(replaced.getStatus()).isEqualTo(ImportStatus.OK);
        assertThat(replaced.getSupersedesImportId()).isEqualTo(first.getId());
        assertThat(replaced.getVersionNo()).isEqualTo(2);
        assertThat(transactionRepository.findByCompanyIdAndPeriod(company.getId(), "2026-01"))
            .singleElement()
            .satisfies(tx -> assertThat(tx.getAmount().toPlainString()).isEqualTo("120.00"));
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

    private static MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile(
            "file",
            filename,
            "text/csv",
            content.strip().concat("\n").getBytes(StandardCharsets.UTF_8)
        );
    }
}
