package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.MacroContextDto;
import com.asecon.enterpriseiq.dto.MacroMetricDto;
import com.asecon.enterpriseiq.model.MacroObservation;
import com.asecon.enterpriseiq.model.MacroSeries;
import com.asecon.enterpriseiq.repo.MacroObservationRepository;
import com.asecon.enterpriseiq.repo.MacroSeriesRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Service
public class MacroDataService {
    private static final Duration STALE_AFTER = Duration.ofHours(12);

    private final MacroSeriesRepository seriesRepository;
    private final MacroObservationRepository observationRepository;
    private final RestClient restClient;

    @Value("${app.macro.ine.ipc-table-id:24077}")
    private String ineIpcTableId;

    @Value("${app.macro.bde.euribor1y-series:D_1NBAF472}")
    private String bdeEuribor1ySeries;

    @Value("${app.macro.ecb.usd-eur-series:EXR/M.USD.EUR.SP00.A}")
    private String ecbUsdEurSeries;

    public MacroDataService(MacroSeriesRepository seriesRepository,
                            MacroObservationRepository observationRepository,
                            RestClient.Builder restClientBuilder) {
        this.seriesRepository = seriesRepository;
        this.observationRepository = observationRepository;
        this.restClient = restClientBuilder.build();
    }

    @Transactional
    public void refreshIfStale() {
        refreshIneIpcIfStale();
        refreshBdeEuriborIfStale();
        refreshEcbUsdEurIfStale();
    }

    @Transactional
    public void refreshAll() {
        refreshIneIpc(true);
        refreshBdeEuribor(true);
        refreshEcbUsdEur(true);
    }

    @Transactional(readOnly = true)
    public MacroContextDto context(String period) {
        String resolvedPeriod = (period == null || period.isBlank()) ? null : period.trim();

        MacroSeries ipc = seriesRepository.findByProviderAndCode("INE", ineIpcTableId).orElse(null);
        MacroSeries euribor = seriesRepository.findByProviderAndCode("BDE", bdeEuribor1ySeries).orElse(null);
        MacroSeries usdEur = seriesRepository.findByProviderAndCode("ECB", ecbUsdEurSeries).orElse(null);

        String latestPeriod = firstNonBlank(
            resolvedPeriod,
            latestPeriod(ipc),
            latestPeriod(euribor),
            latestPeriod(usdEur)
        );
        if (latestPeriod == null) latestPeriod = YearMonth.now().toString();

        BigDecimal inflationYoy = inflationYoyPct(ipc, latestPeriod);
        BigDecimal euriborVal = valueAtOrBefore(euribor, latestPeriod);
        BigDecimal usdEurVal = valueAtOrBefore(usdEur, latestPeriod);

        Instant updatedAt = maxInstant(
            ipc == null ? null : ipc.getLastFetchedAt(),
            euribor == null ? null : euribor.getLastFetchedAt(),
            usdEur == null ? null : usdEur.getLastFetchedAt()
        );

        return new MacroContextDto(
            latestPeriod,
            updatedAt,
            new MacroMetricDto("IPC (YoY)", inflationYoy, "%", "INE"),
            new MacroMetricDto("Euribor 1 año", euriborVal, "%", "Banco de España"),
            new MacroMetricDto("USD/EUR", usdEurVal, "USD", "BCE")
        );
    }

    private void refreshIneIpcIfStale() {
        MacroSeries series = seriesRepository.findByProviderAndCode("INE", ineIpcTableId).orElse(null);
        if (series == null || isStale(series.getLastFetchedAt())) refreshIneIpc(false);
    }

    private void refreshBdeEuriborIfStale() {
        MacroSeries series = seriesRepository.findByProviderAndCode("BDE", bdeEuribor1ySeries).orElse(null);
        if (series == null || isStale(series.getLastFetchedAt())) refreshBdeEuribor(false);
    }

    private void refreshEcbUsdEurIfStale() {
        MacroSeries series = seriesRepository.findByProviderAndCode("ECB", ecbUsdEurSeries).orElse(null);
        if (series == null || isStale(series.getLastFetchedAt())) refreshEcbUsdEur(false);
    }

    private void refreshIneIpc(boolean force) {
        MacroSeries series = seriesRepository.findByProviderAndCode("INE", ineIpcTableId).orElse(null);
        if (!force && series != null && !isStale(series.getLastFetchedAt())) return;
        try {
            String url = "https://servicios.ine.es/wstempus/js/ES/DATOS_TABLA/" + ineIpcTableId + "?nult=36&tip=A";
            JsonNode root = restClient.get().uri(url).retrieve().body(JsonNode.class);
            if (root == null) return;

            String name = text(root, "Nombre", "IPC");
            String unit = text(root, "T3_Unidad", null);
            series = upsertSeries("INE", ineIpcTableId, name, unit, "M");

            JsonNode data = root.get("Data");
            if (data != null && data.isArray()) {
                for (JsonNode row : data) {
                    String fecha = text(row, "Fecha", null);
                    if (fecha == null || fecha.length() < 7) continue;
                    String p = fecha.substring(0, 7);
                    BigDecimal v = row.hasNonNull("Valor") ? row.get("Valor").decimalValue() : null;
                    upsertObservation(series, p, v);
                }
            }

            series.setLastFetchedAt(Instant.now());
            series.setUpdatedAt(Instant.now());
            seriesRepository.save(series);
        } catch (Exception ignored) {}
    }

    private void refreshBdeEuribor(boolean force) {
        MacroSeries series = seriesRepository.findByProviderAndCode("BDE", bdeEuribor1ySeries).orElse(null);
        if (!force && series != null && !isStale(series.getLastFetchedAt())) return;
        try {
            String url = "https://app.bde.es/bierest/resources/srdatosapp/listaSeries?idioma=es&series=" + bdeEuribor1ySeries + "&rango=60M";
            JsonNode root = restClient.get().uri(url).retrieve().body(JsonNode.class);
            JsonNode first = root == null ? null : root.path("value").path(0);
            if (first == null || first.isMissingNode()) return;

            String name = text(first, "descripcionCorta", "Serie BdE");
            String unit = text(first, "simbolo", null);
            String freq = text(first, "codFrecuencia", "M");
            series = upsertSeries("BDE", bdeEuribor1ySeries, name, unit, freq);

            JsonNode fechas = first.path("fechas");
            JsonNode valores = first.path("valores");
            if (fechas.isArray() && valores.isArray()) {
                int n = Math.min(fechas.size(), valores.size());
                for (int i = 0; i < n; i++) {
                    String fecha = fechas.get(i).asText(null);
                    if (fecha == null || fecha.length() < 7) continue;
                    String p = fecha.substring(0, 7);
                    JsonNode rawV = valores.get(i);
                    BigDecimal v = (rawV != null && rawV.isNumber()) ? rawV.decimalValue() : null;
                    upsertObservation(series, p, v);
                }
            }

            series.setLastFetchedAt(Instant.now());
            series.setUpdatedAt(Instant.now());
            seriesRepository.save(series);
        } catch (Exception ignored) {}
    }

    private void refreshEcbUsdEur(boolean force) {
        MacroSeries series = seriesRepository.findByProviderAndCode("ECB", ecbUsdEurSeries).orElse(null);
        if (!force && series != null && !isStale(series.getLastFetchedAt())) return;
        try {
            String url = "https://data-api.ecb.europa.eu/service/data/" + ecbUsdEurSeries + "?lastNObservations=36";
            String xml = restClient.get().uri(url).retrieve().body(String.class);
            if (xml == null || xml.isBlank()) return;

            Document doc = parseXml(xml);
            NodeList titles = doc.getElementsByTagNameNS("*", "Value");
            String title = null;
            for (int i = 0; i < titles.getLength(); i++) {
                Element el = (Element) titles.item(i);
                if ("TITLE".equals(el.getAttribute("id"))) {
                    title = el.getAttribute("value");
                    break;
                }
            }

            series = upsertSeries("ECB", ecbUsdEurSeries, title == null ? "USD/EUR" : title, "USD", "M");

            NodeList obs = doc.getElementsByTagNameNS("*", "Obs");
            for (int i = 0; i < obs.getLength(); i++) {
                Element o = (Element) obs.item(i);
                String p = null;
                BigDecimal v = null;
                NodeList children = o.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    if (!(children.item(j) instanceof Element e)) continue;
                    String local = e.getLocalName();
                    if ("ObsDimension".equals(local)) p = e.getAttribute("value");
                    if ("ObsValue".equals(local)) {
                        String raw = e.getAttribute("value");
                        try { v = raw == null || raw.isBlank() ? null : new BigDecimal(raw); } catch (Exception ignored) {}
                    }
                }
                if (p != null && p.length() >= 7) {
                    String period = p.substring(0, 7);
                    upsertObservation(series, period, v);
                }
            }

            series.setLastFetchedAt(Instant.now());
            series.setUpdatedAt(Instant.now());
            seriesRepository.save(series);
        } catch (Exception ignored) {}
    }

    private MacroSeries upsertSeries(String provider, String code, String name, String unit, String frequency) {
        MacroSeries s = seriesRepository.findByProviderAndCode(provider, code).orElse(null);
        if (s == null) {
            s = new MacroSeries();
            s.setProvider(provider);
            s.setCode(code);
            s.setCreatedAt(Instant.now());
        }
        s.setName(name == null ? (provider + ":" + code) : name);
        s.setUnit(unit);
        s.setFrequency(frequency);
        s.setUpdatedAt(Instant.now());
        return seriesRepository.save(s);
    }

    private void upsertObservation(MacroSeries series, String period, BigDecimal value) {
        if (series == null || series.getId() == null) return;
        MacroObservation obs = observationRepository.findBySeries_IdAndPeriod(series.getId(), period).orElse(null);
        if (obs == null) {
            obs = new MacroObservation();
            obs.setSeries(series);
            obs.setPeriod(period);
            obs.setCreatedAt(Instant.now());
        }
        obs.setValue(value);
        observationRepository.save(obs);
    }

    private static boolean isStale(Instant lastFetchedAt) {
        if (lastFetchedAt == null) return true;
        return lastFetchedAt.isBefore(Instant.now().minus(STALE_AFTER));
    }

    private String latestPeriod(MacroSeries series) {
        if (series == null || series.getId() == null) return null;
        return observationRepository.findTop1BySeries_IdOrderByPeriodDesc(series.getId()).map(MacroObservation::getPeriod).orElse(null);
    }

    private BigDecimal valueAtOrBefore(MacroSeries series, String period) {
        if (series == null || series.getId() == null) return null;
        return observationRepository.findTop1BySeries_IdAndPeriodLessThanEqualOrderByPeriodDesc(series.getId(), period)
            .map(MacroObservation::getValue)
            .orElse(null);
    }

    private BigDecimal inflationYoyPct(MacroSeries ipc, String period) {
        if (ipc == null || ipc.getId() == null) return null;
        try {
            YearMonth ym = YearMonth.parse(period);
            String prev = ym.minusMonths(12).toString();
            BigDecimal vNow = observationRepository.findBySeries_IdAndPeriod(ipc.getId(), period).map(MacroObservation::getValue).orElse(null);
            BigDecimal vPrev = observationRepository.findBySeries_IdAndPeriod(ipc.getId(), prev).map(MacroObservation::getValue).orElse(null);
            if (vNow == null || vPrev == null || vPrev.compareTo(BigDecimal.ZERO) == 0) return null;
            return vNow.divide(vPrev, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant maxInstant(Instant a, Instant b, Instant c) {
        Instant max = null;
        if (a != null) max = a;
        if (b != null && (max == null || b.isAfter(max))) max = b;
        if (c != null && (max == null || c.isAfter(max))) max = c;
        return max;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? fallback : v.asText(fallback);
    }

    private static Document parseXml(String xml) throws Exception {
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        var db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}

