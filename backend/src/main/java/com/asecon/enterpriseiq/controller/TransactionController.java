package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.TransactionDto;
import com.asecon.enterpriseiq.dto.TransactionAnalyticsDto;
import com.asecon.enterpriseiq.dto.TransactionPageDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.Transaction;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.TransactionAnalyticsService;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/companies/{companyId}/transactions")
@PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
public class TransactionController {
    private final TransactionRepository transactionRepository;
    private final AccessService accessService;
    private final TransactionAnalyticsService transactionAnalyticsService;

    public TransactionController(TransactionRepository transactionRepository,
                                 AccessService accessService,
                                 TransactionAnalyticsService transactionAnalyticsService) {
        this.transactionRepository = transactionRepository;
        this.accessService = accessService;
        this.transactionAnalyticsService = transactionAnalyticsService;
    }

    @GetMapping
    public TransactionPageDto list(@PathVariable Long companyId,
                                   @RequestParam(required = false) String period,
                                   @RequestParam(required = false) LocalDate fromDate,
                                   @RequestParam(required = false) LocalDate toDate,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) BigDecimal minAmount,
                                   @RequestParam(required = false) BigDecimal maxAmount,
                                   @RequestParam(required = false) String direction,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Sort sort = Sort.by(Sort.Order.asc("txnDate"), Sort.Order.asc("id"));
        PageRequest pageable = PageRequest.of(safePage, safeSize, sort);

        Specification<Transaction> spec = buildSpec(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction);
        Page<Transaction> res = transactionRepository.findAll(spec, pageable);
        List<TransactionDto> items = res.getContent().stream().map(this::toDto).toList();
        return new TransactionPageDto(items, res.getNumber(), res.getSize(), res.getTotalElements(), res.getTotalPages(), res.hasNext());
    }

    @GetMapping("/analytics")
    public TransactionAnalyticsDto analytics(@PathVariable Long companyId,
                                             @RequestParam(required = false) String period,
                                             @RequestParam(required = false) LocalDate fromDate,
                                             @RequestParam(required = false) LocalDate toDate,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) BigDecimal minAmount,
                                             @RequestParam(required = false) BigDecimal maxAmount,
                                             @RequestParam(required = false) String direction,
                                             @RequestParam(required = false) Integer topN) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        return transactionAnalyticsService.analytics(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction, topN);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(@PathVariable Long companyId,
                                                          @RequestParam(required = false) String period,
                                                          @RequestParam(required = false) LocalDate fromDate,
                                                          @RequestParam(required = false) LocalDate toDate,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) BigDecimal minAmount,
                                                          @RequestParam(required = false) BigDecimal maxAmount,
                                                          @RequestParam(required = false) String direction) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);

        Specification<Transaction> spec = buildSpec(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction);
        Sort sort = Sort.by(Sort.Order.asc("txnDate"), Sort.Order.asc("id"));

        String filename = ("transactions-" + (period != null && !period.isBlank() ? period : "filtered") + ".csv")
            .replaceAll("[^a-zA-Z0-9._-]", "_");

        StreamingResponseBody body = outputStream -> {
            try (var writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                writer.write("id,company_id,period,txn_date,amount,description,counterparty\n");
                List<Transaction> rows = transactionRepository.findAll(spec, sort);
                for (Transaction tx : rows) {
                    writer.write(csv(tx.getId()));
                    writer.write(",");
                    writer.write(csv(companyId));
                    writer.write(",");
                    writer.write(csv(tx.getPeriod()));
                    writer.write(",");
                    writer.write(csv(tx.getTxnDate()));
                    writer.write(",");
                    writer.write(csv(tx.getAmount()));
                    writer.write(",");
                    writer.write(csv(tx.getDescription()));
                    writer.write(",");
                    writer.write(csv(tx.getCounterparty()));
                    writer.write("\n");
                }
                writer.flush();
            }
        };

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(body);
    }

    private TransactionDto toDto(Transaction tx) {
        return new TransactionDto(
            tx.getId(),
            tx.getPeriod(),
            tx.getTxnDate(),
            tx.getDescription(),
            tx.getAmount(),
            tx.getCounterparty()
        );
    }

    private static Specification<Transaction> buildSpec(Long companyId,
                                                        String period,
                                                        LocalDate fromDate,
                                                        LocalDate toDate,
                                                        String q,
                                                        BigDecimal minAmount,
                                                        BigDecimal maxAmount,
                                                        String direction) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("company").get("id"), companyId));

            if (period != null && !period.isBlank()) {
                predicates.add(cb.equal(root.get("period"), period));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("txnDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("txnDate"), toDate));
            }
            if (minAmount != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
            }
            if (maxAmount != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
            }
            if (direction != null && !direction.isBlank()) {
                String d = direction.toLowerCase(Locale.ROOT).trim();
                if (Objects.equals(d, "in") || Objects.equals(d, "inflow") || Objects.equals(d, "inflows")) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), BigDecimal.ZERO));
                } else if (Objects.equals(d, "out") || Objects.equals(d, "outflow") || Objects.equals(d, "outflows")) {
                    predicates.add(cb.lessThan(root.get("amount"), BigDecimal.ZERO));
                }
            }

            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase(Locale.ROOT).trim() + "%";
                var desc = cb.lower(root.get("description"));
                var cp = cb.lower(root.get("counterparty"));
                predicates.add(cb.or(
                    cb.like(desc, like),
                    cb.like(cb.coalesce(cp, ""), like)
                ));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
