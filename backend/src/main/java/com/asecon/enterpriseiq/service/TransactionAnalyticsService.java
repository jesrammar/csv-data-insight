package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.TransactionAnalyticsDto;
import com.asecon.enterpriseiq.dto.TransactionAnomalyDto;
import com.asecon.enterpriseiq.dto.TransactionCategoryAggDto;
import com.asecon.enterpriseiq.dto.TransactionCounterpartyAggDto;
import com.asecon.enterpriseiq.dto.TransactionDailyAggDto;
import com.asecon.enterpriseiq.dto.TransactionTotalsDto;
import com.asecon.enterpriseiq.model.Transaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TransactionAnalyticsService {
    private final EntityManager em;

    public TransactionAnalyticsService(EntityManager em) {
        this.em = em;
    }

    public TransactionAnalyticsDto analytics(Long companyId,
                                             String period,
                                             LocalDate fromDate,
                                             LocalDate toDate,
                                             String q,
                                             BigDecimal minAmount,
                                             BigDecimal maxAmount,
                                             String direction,
                                             Integer topN) {
        int limit = topN == null ? 10 : Math.min(Math.max(topN, 1), 50);

        TransactionTotalsDto totals = computeTotals(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction);
        List<TransactionDailyAggDto> daily = computeDaily(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction);
        List<TransactionCounterpartyAggDto> top = computeTopCounterparties(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction, limit);
        List<TransactionCategoryAggDto> categories = computeCategories(companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction, 12);
        List<TransactionAnomalyDto> anomalies = detectDailyAnomalies(daily);
        return new TransactionAnalyticsDto(totals, daily, top, categories, anomalies);
    }

    private TransactionTotalsDto computeTotals(Long companyId,
                                               String period,
                                               LocalDate fromDate,
                                               LocalDate toDate,
                                               String q,
                                               BigDecimal minAmount,
                                               BigDecimal maxAmount,
                                               String direction) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createTupleQuery();
        Root<Transaction> tx = cq.from(Transaction.class);

        Expression<BigDecimal> amount = tx.get("amount");
        Expression<BigDecimal> inflows = cb.sum(cb.<BigDecimal>selectCase().when(cb.greaterThanOrEqualTo(amount, BigDecimal.ZERO), amount).otherwise(BigDecimal.ZERO));
        Expression<BigDecimal> outflows = cb.sum(cb.<BigDecimal>selectCase().when(cb.lessThan(amount, BigDecimal.ZERO), amount).otherwise(BigDecimal.ZERO));
        Expression<BigDecimal> net = cb.sum(amount);
        Expression<Long> cnt = cb.count(tx.get("id"));

        cq.multiselect(
            inflows.alias("inflows"),
            outflows.alias("outflows"),
            net.alias("net"),
            cnt.alias("count")
        );
        cq.where(predicates(cb, tx, companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction).toArray(Predicate[]::new));

        Tuple t = em.createQuery(cq).getSingleResult();
        BigDecimal in = t.get("inflows", BigDecimal.class);
        BigDecimal out = t.get("outflows", BigDecimal.class);
        BigDecimal n = t.get("net", BigDecimal.class);
        Long c = t.get("count", Long.class);
        return new TransactionTotalsDto(
            in == null ? BigDecimal.ZERO : in,
            out == null ? BigDecimal.ZERO : out,
            n == null ? BigDecimal.ZERO : n,
            c == null ? 0 : c
        );
    }

    private List<TransactionDailyAggDto> computeDaily(Long companyId,
                                                      String period,
                                                      LocalDate fromDate,
                                                      LocalDate toDate,
                                                      String q,
                                                      BigDecimal minAmount,
                                                      BigDecimal maxAmount,
                                                      String direction) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createTupleQuery();
        Root<Transaction> tx = cq.from(Transaction.class);

        Expression<LocalDate> date = tx.get("txnDate");
        Expression<BigDecimal> amount = tx.get("amount");
        Expression<BigDecimal> inflows = cb.sum(cb.<BigDecimal>selectCase().when(cb.greaterThanOrEqualTo(amount, BigDecimal.ZERO), amount).otherwise(BigDecimal.ZERO));
        Expression<BigDecimal> outflows = cb.sum(cb.<BigDecimal>selectCase().when(cb.lessThan(amount, BigDecimal.ZERO), amount).otherwise(BigDecimal.ZERO));
        Expression<BigDecimal> net = cb.sum(amount);
        Expression<Long> cnt = cb.count(tx.get("id"));

        cq.multiselect(
            date.alias("date"),
            inflows.alias("inflows"),
            outflows.alias("outflows"),
            net.alias("net"),
            cnt.alias("count")
        );
        cq.where(predicates(cb, tx, companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction).toArray(Predicate[]::new));
        cq.groupBy(date);
        cq.orderBy(cb.asc(date));

        List<Tuple> rows = em.createQuery(cq).getResultList();
        return rows.stream().map(r -> new TransactionDailyAggDto(
            r.get("date", LocalDate.class),
            nz(r.get("inflows", BigDecimal.class)),
            nz(r.get("outflows", BigDecimal.class)),
            nz(r.get("net", BigDecimal.class)),
            r.get("count", Long.class) == null ? 0 : r.get("count", Long.class)
        )).toList();
    }

    private List<TransactionCounterpartyAggDto> computeTopCounterparties(Long companyId,
                                                                         String period,
                                                                         LocalDate fromDate,
                                                                         LocalDate toDate,
                                                                         String q,
                                                                         BigDecimal minAmount,
                                                                         BigDecimal maxAmount,
                                                                         String direction,
                                                                         int limit) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createTupleQuery();
        Root<Transaction> tx = cq.from(Transaction.class);

        Expression<String> cp = cb.coalesce(tx.get("counterparty"), "(sin contrapartida)");
        Expression<BigDecimal> sum = cb.sum(tx.get("amount"));
        Expression<Long> cnt = cb.count(tx.get("id"));

        cq.multiselect(
            cp.alias("counterparty"),
            sum.alias("total"),
            cnt.alias("count")
        );
        cq.where(predicates(cb, tx, companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction).toArray(Predicate[]::new));
        cq.groupBy(cp);
        cq.orderBy(cb.desc(cb.abs(sum)));

        TypedQuery<Tuple> query = em.createQuery(cq);
        query.setMaxResults(limit);
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(r -> new TransactionCounterpartyAggDto(
            r.get("counterparty", String.class),
            nz(r.get("total", BigDecimal.class)),
            r.get("count", Long.class) == null ? 0 : r.get("count", Long.class)
        )).toList();
    }

    private List<TransactionCategoryAggDto> computeCategories(Long companyId,
                                                             String period,
                                                             LocalDate fromDate,
                                                             LocalDate toDate,
                                                             String q,
                                                             BigDecimal minAmount,
                                                             BigDecimal maxAmount,
                                                             String direction,
                                                             int limit) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createTupleQuery();
        Root<Transaction> tx = cq.from(Transaction.class);

        Expression<String> desc = cb.coalesce(tx.get("description").as(String.class), cb.literal(""));
        Expression<String> cp = cb.coalesce(tx.get("counterparty").as(String.class), cb.literal(""));
        Expression<String> hay = cb.lower(cb.concat(cb.concat(desc, " "), cp));

        Expression<String> category = cb.<String>selectCase()
            .when(cb.like(hay, "%nomina%"), "Nóminas")
            .when(cb.like(hay, "%nómina%"), "Nóminas")
            .when(cb.like(hay, "%seguridad social%"), "Seguridad Social")
            .when(cb.like(hay, "%ss%"), "Seguridad Social")
            .when(cb.like(hay, "%aeat%"), "Impuestos")
            .when(cb.like(hay, "%hacienda%"), "Impuestos")
            .when(cb.like(hay, "%iva%"), "Impuestos")
            .when(cb.like(hay, "%irpf%"), "Impuestos")
            .when(cb.like(hay, "%alquiler%"), "Alquiler")
            .when(cb.like(hay, "%rent%"), "Alquiler")
            .when(cb.like(hay, "%prestam%"), "Financiación")
            .when(cb.like(hay, "%loan%"), "Financiación")
            .when(cb.like(hay, "%leasing%"), "Financiación")
            .when(cb.like(hay, "%tarjeta%"), "Banca / Tarjeta")
            .when(cb.like(hay, "%comision%"), "Banca / Tarjeta")
            .when(cb.like(hay, "%commission%"), "Banca / Tarjeta")
            .when(cb.like(hay, "%tpv%"), "Cobros TPV")
            .when(cb.like(hay, "%stripe%"), "Cobros TPV")
            .when(cb.like(hay, "%paypal%"), "Cobros TPV")
            .when(cb.like(hay, "%amazon%"), "Proveedores")
            .when(cb.like(hay, "%google%"), "Marketing / Ads")
            .when(cb.like(hay, "%meta%"), "Marketing / Ads")
            .when(cb.like(hay, "%facebook%"), "Marketing / Ads")
            .when(cb.like(hay, "%ads%"), "Marketing / Ads")
            .when(cb.like(hay, "%transfer%"), "Transferencias")
            .when(cb.like(hay, "%traspas%"), "Transferencias")
            .otherwise("Otros");

        Expression<BigDecimal> amount = tx.get("amount");
        Expression<BigDecimal> inflows = cb.sum(cb.<BigDecimal>selectCase().when(cb.greaterThanOrEqualTo(amount, BigDecimal.ZERO), amount).otherwise(BigDecimal.ZERO));
        Expression<BigDecimal> outflows = cb.sum(cb.<BigDecimal>selectCase().when(cb.lessThan(amount, BigDecimal.ZERO), amount).otherwise(BigDecimal.ZERO));
        Expression<BigDecimal> total = cb.sum(amount);
        Expression<Long> cnt = cb.count(tx.get("id"));

        cq.multiselect(
            category.alias("category"),
            total.alias("total"),
            inflows.alias("inflows"),
            outflows.alias("outflows"),
            cnt.alias("count")
        );
        cq.where(predicates(cb, tx, companyId, period, fromDate, toDate, q, minAmount, maxAmount, direction).toArray(Predicate[]::new));
        cq.groupBy(category);
        cq.orderBy(cb.desc(cb.abs(total)));

        var query = em.createQuery(cq);
        query.setMaxResults(limit);
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(r -> new TransactionCategoryAggDto(
            r.get("category", String.class),
            nz(r.get("total", BigDecimal.class)),
            nz(r.get("inflows", BigDecimal.class)),
            nz(r.get("outflows", BigDecimal.class)),
            r.get("count", Long.class) == null ? 0 : r.get("count", Long.class)
        )).toList();
    }

    private static List<TransactionAnomalyDto> detectDailyAnomalies(List<TransactionDailyAggDto> daily) {
        if (daily == null || daily.size() < 10) return List.of();

        List<BigDecimal> nets = daily.stream().map(TransactionDailyAggDto::net).filter(Objects::nonNull).toList();
        if (nets.size() < 10) return List.of();

        List<BigDecimal> absDevs = new ArrayList<>();
        BigDecimal median = median(nets);
        for (BigDecimal v : nets) {
            absDevs.add(v.subtract(median).abs());
        }
        BigDecimal mad = median(absDevs);
        if (mad.compareTo(BigDecimal.ZERO) == 0) return List.of();

        List<TransactionAnomalyDto> anomalies = new ArrayList<>();
        for (TransactionDailyAggDto d : daily) {
            if (d == null || d.net() == null || d.date() == null) continue;
            double score = 0.6745 * d.net().subtract(median).doubleValue() / mad.doubleValue(); // robust z-score
            if (Math.abs(score) >= 3.5) {
                anomalies.add(new TransactionAnomalyDto(
                    d.date(),
                    d.net(),
                    score,
                    "Pico vs mediana (robust z-score)"
                ));
            }
        }

        if (anomalies.isEmpty()) return List.of();
        anomalies.sort(Comparator.comparingDouble(a -> Math.abs(a.score())));
        Collections.reverse(anomalies);
        return anomalies.stream().limit(10).collect(Collectors.toList());
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return BigDecimal.ZERO;
        List<BigDecimal> sorted = values.stream().filter(v -> v != null).sorted().toList();
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        BigDecimal a = sorted.get((n / 2) - 1);
        BigDecimal b = sorted.get(n / 2);
        return a.add(b).divide(BigDecimal.valueOf(2), java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static List<Predicate> predicates(jakarta.persistence.criteria.CriteriaBuilder cb,
                                              Root<Transaction> tx,
                                              Long companyId,
                                              String period,
                                              LocalDate fromDate,
                                              LocalDate toDate,
                                              String q,
                                              BigDecimal minAmount,
                                              BigDecimal maxAmount,
                                              String direction) {
        var preds = new ArrayList<Predicate>();
        preds.add(cb.equal(tx.get("company").get("id"), companyId));

        if (period != null && !period.isBlank()) {
            preds.add(cb.equal(tx.get("period"), period));
        }
        if (fromDate != null) {
            preds.add(cb.greaterThanOrEqualTo(tx.get("txnDate"), fromDate));
        }
        if (toDate != null) {
            preds.add(cb.lessThanOrEqualTo(tx.get("txnDate"), toDate));
        }
        if (minAmount != null) {
            preds.add(cb.greaterThanOrEqualTo(tx.get("amount"), minAmount));
        }
        if (maxAmount != null) {
            preds.add(cb.lessThanOrEqualTo(tx.get("amount"), maxAmount));
        }
        if (direction != null && !direction.isBlank()) {
            String d = direction.toLowerCase(Locale.ROOT).trim();
            if (d.equals("in") || d.equals("inflow") || d.equals("inflows")) {
                preds.add(cb.greaterThanOrEqualTo(tx.get("amount"), BigDecimal.ZERO));
            } else if (d.equals("out") || d.equals("outflow") || d.equals("outflows")) {
                preds.add(cb.lessThan(tx.get("amount"), BigDecimal.ZERO));
            }
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.toLowerCase(Locale.ROOT).trim() + "%";
            Expression<String> desc = cb.lower(tx.get("description"));
            Expression<String> cp = cb.lower(cb.coalesce(tx.get("counterparty"), ""));
            preds.add(cb.or(cb.like(desc, like), cb.like(cp, like)));
        }

        return preds;
    }
}
