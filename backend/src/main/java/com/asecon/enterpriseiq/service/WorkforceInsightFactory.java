package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.WorkforceGestorDto;
import com.asecon.enterpriseiq.dto.WorkforceHistoryDto;
import com.asecon.enterpriseiq.dto.WorkforceHistoryPointDto;
import com.asecon.enterpriseiq.dto.WorkforceInsightsDto;
import com.asecon.enterpriseiq.dto.WorkforceKpiDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostsDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WorkforceInsightFactory {
    private static final Locale LOCALE = Locale.forLanguageTag("es-ES");
    private static final String EMPTY = "—";

    private WorkforceInsightFactory() {}

    static WorkforceInsightsDto build(WorkforceSummaryDto summary) {
        if (summary == null) {
            return empty();
        }
        List<WorkforceGestorDto> gestores = summary.gestores() == null ? List.of() : summary.gestores();
        if (gestores.isEmpty()) {
            return empty();
        }

        WorkforceKpiDto kpis = summary.kpis();
        WorkforceLaborCostsDto laborCosts = summary.laborCosts();
        WorkforceLaborCostsDto pairedLaborCosts = summary.pairedLaborCosts();
        WorkforceHistoryDto history = summary.history();
        boolean hasEconomicData = laborCosts != null;
        boolean hasPairedCosts = pairedLaborCosts != null;
        boolean annualSnapshotCosts = summary.laborCostsImport() != null && Boolean.TRUE.equals(summary.laborCostsImport().annualCoverage());

        List<String> executive = distinctLimited(items(
            "La base actual recoge %s clientes totales, %s activos, %s de baja y %s gestores.".formatted(
                fmtInteger(kpis == null ? 0 : kpis.totalClients()),
                fmtInteger(kpis == null ? 0 : kpis.activeClients()),
                fmtInteger(kpis == null ? 0 : kpis.inactiveClients()),
                fmtInteger(kpis == null ? 0 : kpis.totalGestores())
            ),
            clientDistributionInsight(gestores),
            dualLeaderInsight(
                gestores,
                WorkforceGestorDto::totalMinutas,
                WorkforceGestorDto::totalVolumenAsientos,
                "minutos",
                "volumen contable"
            ),
            leaderInsight(
                gestores,
                WorkforceGestorDto::cargaMedia,
                0.5d,
                0.08d,
                "%s presenta la carga media mas alta con %s. Conviene leerla como intensidad de cartera, no como juicio de rendimiento."
            ),
            costLeadershipInsight(gestores, pairedLaborCosts, annualSnapshotCosts),
            snapshotInsight(history)
        ), 6);

        List<String> priorities = distinctLimited(items(
            concentrationPriority(gestores, row -> (double) row.totalClients(), "clientes"),
            concentrationPriority(gestores, WorkforceGestorDto::totalMinutas, "minutos"),
            pendingServicesPriority(gestores),
            !hasEconomicData ? "Carga el fichero de costes laborales para abrir la lectura economica por gestor." : null,
            hasEconomicData && !hasPairedCosts
                ? "Los costes estan disponibles, pero el cruce coste/actividad no aplica al snapshot actual por falta de periodo compatible."
                : null,
            laborCosts != null && laborCosts.reviewCount() > 0
                ? "Revisa las coincidencias de costes en estado REVIEW antes de interpretar ratios economicos."
                : null,
            missingMetricPriority(gestores),
            history == null || history.imports() == null || history.imports().size() < 2
                ? "Aun no existe evolucion entre importaciones. La comparativa entre cargas se activara con una segunda foto."
                : null
        ), 4);

        List<String> costActivity = !hasPairedCosts
            ? List.of()
            : distinctLimited(items(
                costLeadershipInsight(gestores, pairedLaborCosts, annualSnapshotCosts),
                costRatioInsight(gestores),
                inverseCostInsight(gestores),
                dualLeaderInsight(
                    gestores,
                    WorkforceGestorDto::costeLaboralAnual,
                    WorkforceGestorDto::totalVolumenAsientos,
                    "coste laboral",
                    "volumen de asientos"
                )
            ), 4);

        List<String> findings = distinctLimited(items(
            clientDistributionInsight(gestores),
            dualLeaderInsight(
                gestores,
                WorkforceGestorDto::totalMinutas,
                WorkforceGestorDto::totalVolumenAsientos,
                "minutos",
                "asientos"
            ),
            leaderInsight(
                gestores,
                WorkforceGestorDto::cargaMedia,
                0.5d,
                0.08d,
                "%s mantiene la mayor carga media con %s."
            ),
            costLeadershipInsight(gestores, pairedLaborCosts, annualSnapshotCosts),
            snapshotInsight(history)
        ), 5);

        List<String> reviewPoints = distinctLimited(items(
            concentrationPriority(gestores, row -> (double) row.totalClients(), "cartera"),
            concentrationPriority(gestores, WorkforceGestorDto::totalMinutas, "tiempo asignado"),
            pendingServicesPriority(gestores),
            laborCosts != null && laborCosts.reviewCount() > 0
                ? "Existen filas de costes en REVIEW; conviene resolverlas antes de usar esos ratios como referencia."
                : null,
            outlierVsAverage(gestores, WorkforceGestorDto::costePorCliente, 0.15d,
                "%s supera de forma material el coste por cliente total medio del equipo.")
        ), 5);

        List<String> recommendations = distinctLimited(items(
            concentrationRecommendation(gestores, row -> (double) row.totalClients(), "reparto de cartera"),
            concentrationRecommendation(gestores, WorkforceGestorDto::totalMinutas, "reparto de minutos"),
            !hasEconomicData
                ? "Completar la segunda carga de costes laborales para revisar la dimension economica del equipo."
                : hasPairedCosts
                    ? "Cruzar coste laboral con minutos y asientos para revisar intensidad operativa sin convertir el analisis en un ranking de personas."
                    : "Alinear el periodo de Workforce y costes si necesitas activar ratios coste/actividad en el mismo snapshot.",
            history == null || history.imports() == null || history.imports().size() < 2
                ? "Crear una segunda carga de Workforce para validar tendencias antes de extraer conclusiones sobre evolucion."
                : "Contrastar las variaciones entre cargas con cambios reales de cartera para separar tendencia de fotografia puntual.",
            "Mantener separadas las nociones de coste, carga y productividad: representan dimensiones distintas del trabajo."
        ), 5);

        List<String> limitations = distinctLimited(items(
            "Los ratios economicos usan como denominador clientes totales, no solo clientes activos.",
            missingMetricPriority(gestores),
            !hasEconomicData ? "No hay fichero de costes laborales en esta carga." : null,
            hasEconomicData && !hasPairedCosts ? "Los costes cargados no se cruzan con este snapshot porque no comparten periodo compatible." : null,
            history == null || history.imports() == null || history.imports().size() < 2
                ? "No existe todavia evolucion entre importaciones; solo hay historico N AS del propio fichero."
                : null
        ), 4);

        return new WorkforceInsightsDto(
            executive,
            priorities,
            costActivity,
            findings,
            reviewPoints,
            recommendations,
            limitations
        );
    }

    private static WorkforceInsightsDto empty() {
        return new WorkforceInsightsDto(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static String clientDistributionInsight(List<WorkforceGestorDto> gestores) {
        MetricLeader leader = leader(gestores, row -> (double) row.totalClients(), 1d, 0.05d);
        Concentration concentration = concentration(gestores, row -> (double) row.totalClients());
        if (leader != null) {
            return "%s registra la mayor cartera total con %s clientes.".formatted(
                leader.gestor(),
                fmtInteger(Math.round(leader.value()))
            );
        }
        if (concentration != null && concentration.share() >= 65d && concentration.leaders().size() < gestores.size()) {
            return "La cartera total se concentra en %s, con %s del conjunto.".formatted(
                String.join(", ", concentration.leaders()),
                fmtPercent(concentration.share())
            );
        }
        return "La cartera total aparece repartida sin un gestor claramente dominante.";
    }

    private static String dualLeaderInsight(List<WorkforceGestorDto> gestores,
                                            MetricExtractor left,
                                            MetricExtractor right,
                                            String leftLabel,
                                            String rightLabel) {
        MetricLeader leftLeader = leader(gestores, left, 1d, 0.05d);
        MetricLeader rightLeader = leader(gestores, right, 1d, 0.05d);
        if (leftLeader == null || rightLeader == null) {
            return null;
        }
        if (leftLeader.gestor().equalsIgnoreCase(rightLeader.gestor())) {
            return "%s lidera tanto %s como %s dentro de la foto actual.".formatted(
                leftLeader.gestor(),
                leftLabel,
                rightLabel
            );
        }
        return "%s lidera %s, mientras %s registra el mayor %s. Conviene revisar la composicion de ambas carteras antes de interpretar productividad.".formatted(
            leftLeader.gestor(),
            leftLabel,
            rightLeader.gestor(),
            rightLabel
        );
    }

    private static String leaderInsight(List<WorkforceGestorDto> gestores,
                                        MetricExtractor extractor,
                                        double absFloor,
                                        double pctFloor,
                                        String template) {
        MetricLeader leader = leader(gestores, extractor, absFloor, pctFloor);
        if (leader == null) {
            return null;
        }
        return template.formatted(leader.gestor(), fmtNumber(leader.value()));
    }

    private static String costLeadershipInsight(List<WorkforceGestorDto> gestores,
                                                WorkforceLaborCostsDto laborCosts,
                                                boolean annualSnapshotCosts) {
        if (laborCosts == null) {
            return null;
        }
        MetricLeader costLeader = leader(gestores, WorkforceGestorDto::costeLaboralAnual, 250d, 0.05d);
        String scopeLabel = annualSnapshotCosts ? "coste laboral anual" : "coste laboral del periodo";
        if (costLeader == null) {
            return "No se observa un gestor claramente dominante en " + scopeLabel + ".";
        }
        return "%s concentra el mayor %s con %s. El dato debe leerse junto a minutos y asientos, no como una medida de rendimiento.".formatted(
            costLeader.gestor(),
            scopeLabel,
            fmtCurrency(costLeader.value())
        );
    }

    private static String costRatioInsight(List<WorkforceGestorDto> gestores) {
        MetricLeader ratioLeader = leader(gestores, WorkforceGestorDto::costePorCliente, 25d, 0.08d);
        if (ratioLeader == null) {
            return "El coste por cliente total no muestra diferencias materiales entre gestores.";
        }
        return "%s presenta el mayor coste por cliente total con %s. Conviene contrastarlo con la mezcla de clientes activos y la intensidad de servicio.".formatted(
            ratioLeader.gestor(),
            fmtCurrency(ratioLeader.value())
        );
    }

    private static String inverseCostInsight(List<WorkforceGestorDto> gestores) {
        MetricLeader minutesLeader = leader(gestores, WorkforceGestorDto::minutasPor1000Coste, 5d, 0.08d);
        if (minutesLeader == null) {
            return "La relacion entre minutos y coste laboral aparece bastante homogenea en esta carga.";
        }
        return "%s registra mas minutos por 1.000 EUR con %s. La lectura habla de intensidad operativa, no de calidad profesional.".formatted(
            minutesLeader.gestor(),
            fmtNumber(minutesLeader.value())
        );
    }

    private static String snapshotInsight(WorkforceHistoryDto history) {
        if (history == null || history.imports() == null || history.imports().size() < 2) {
            return "Todavia no existe comparativa entre importaciones; el seguimiento temporal se limita al historico N AS del fichero actual.";
        }
        List<WorkforceHistoryPointDto> imports = history.imports();
        WorkforceHistoryPointDto current = imports.get(imports.size() - 1);
        WorkforceHistoryPointDto previous = imports.get(imports.size() - 2);
        Double currentMinutes = current.totalMinutas();
        Double previousMinutes = previous.totalMinutas();
        if (currentMinutes != null && previousMinutes != null && Math.abs(currentMinutes - previousMinutes) > Math.max(10d, previousMinutes * 0.05d)) {
            double delta = currentMinutes - previousMinutes;
            return "Entre las dos ultimas cargas, los minutos cambian %s. Conviene validar si refleja un cambio real de cartera o una foto parcial del dato.".formatted(
                signedNumber(delta)
            );
        }
        return "Las dos ultimas importaciones no muestran un cambio material en minutos asignados.";
    }

    private static String concentrationPriority(List<WorkforceGestorDto> gestores,
                                                MetricExtractor extractor,
                                                String label) {
        Concentration concentration = concentration(gestores, extractor);
        if (concentration == null || concentration.share() < 65d || concentration.leaders().size() >= gestores.size()) {
            return null;
        }
        return "El peso conjunto de %s alcanza %s del total de %s.".formatted(
            String.join(", ", concentration.leaders()),
            fmtPercent(concentration.share()),
            label
        );
    }

    private static String pendingServicesPriority(List<WorkforceGestorDto> gestores) {
        long pending = gestores.stream()
            .mapToLong(row -> pending(row.contModelosStates().pending(), row.contModelosStates().unknown())
                + pending(row.isIrpfStates().pending(), row.isIrpfStates().unknown())
                + pending(row.ddccStates().pending(), row.ddccStates().unknown())
                + pending(row.librosStates().pending(), row.librosStates().unknown()))
            .sum();
        if (pending <= 0) {
            return null;
        }
        return "Se detectan %s estados pendientes o desconocidos en servicios transversales.".formatted(fmtInteger(pending));
    }

    private static String missingMetricPriority(List<WorkforceGestorDto> gestores) {
        long missingCarga = gestores.stream().filter(row -> row.cargaMedia() == null).count();
        long missingPct = gestores.stream().filter(row -> row.pctContabilidadMedio() == null).count();
        if (missingCarga == 0 && missingPct == 0) {
            return null;
        }
        List<String> bits = new ArrayList<>();
        if (missingCarga > 0) bits.add("%s gestor(es) sin carga media".formatted(fmtInteger(missingCarga)));
        if (missingPct > 0) bits.add("%s gestor(es) sin %% contabilidad".formatted(fmtInteger(missingPct)));
        return "Persisten vacios de dato: " + String.join("; ", bits) + ".";
    }

    private static String outlierVsAverage(List<WorkforceGestorDto> gestores,
                                           MetricExtractor extractor,
                                           double pctFloor,
                                           String template) {
        MetricLeader leader = leader(gestores, extractor, 1d, pctFloor);
        if (leader == null) {
            return null;
        }
        return template.formatted(leader.gestor());
    }

    private static String concentrationRecommendation(List<WorkforceGestorDto> gestores,
                                                      MetricExtractor extractor,
                                                      String label) {
        Concentration concentration = concentration(gestores, extractor);
        if (concentration == null || concentration.share() < 65d || concentration.leaders().size() >= gestores.size()) {
            return "Mantener seguimiento mensual del %s para detectar concentraciones antes de que se vuelvan estructurales.".formatted(label);
        }
        return "Revisar el %s, especialmente en %s, para valorar si procede redistribucion.".formatted(
            label,
            String.join(", ", concentration.leaders())
        );
    }

    private static MetricLeader leader(List<WorkforceGestorDto> gestores,
                                       MetricExtractor extractor,
                                       double absFloor,
                                       double pctFloor) {
        List<MetricLeader> values = gestores.stream()
            .map(row -> {
                Double value = extractor.extract(row);
                return value == null ? null : new MetricLeader(row.gestor(), value);
            })
            .filter(row -> row != null)
            .sorted(Comparator.comparing(MetricLeader::value).reversed())
            .toList();
        if (values.size() < 2) {
            return values.isEmpty() ? null : values.get(0);
        }
        MetricLeader first = values.get(0);
        MetricLeader second = values.get(1);
        if (Math.abs(first.value() - second.value()) < Math.max(1e-6d, absFloor * 0.5d)) {
            return null;
        }
        double avg = values.stream().mapToDouble(MetricLeader::value).average().orElse(0d);
        double gap = first.value() - second.value();
        double pctGap = second.value() == 0d ? 1d : gap / Math.abs(second.value());
        double avgGap = avg == 0d ? 0d : (first.value() - avg) / Math.abs(avg);
        boolean material = gap >= absFloor || pctGap >= pctFloor || avgGap >= pctFloor;
        return material ? first : null;
    }

    private static Concentration concentration(List<WorkforceGestorDto> gestores, MetricExtractor extractor) {
        List<MetricLeader> values = gestores.stream()
            .map(row -> {
                Double value = extractor.extract(row);
                return value == null ? null : new MetricLeader(row.gestor(), value);
            })
            .filter(row -> row != null)
            .sorted(Comparator.comparing(MetricLeader::value).reversed())
            .toList();
        if (values.isEmpty()) {
            return null;
        }
        double total = values.stream().mapToDouble(MetricLeader::value).sum();
        if (total <= 0d) {
            return null;
        }
        List<String> leaders = values.stream().limit(2).map(MetricLeader::gestor).toList();
        double share = values.stream().limit(2).mapToDouble(MetricLeader::value).sum() / total * 100d;
        return new Concentration(leaders, share);
    }

    private static List<String> distinctLimited(List<String> input, int maxItems) {
        Set<String> seen = new LinkedHashSet<>();
        for (String item : input) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            seen.add(trimmed);
            if (seen.size() >= maxItems) break;
        }
        return List.copyOf(seen);
    }

    private static List<String> items(String... items) {
        return Arrays.asList(items);
    }

    private static long pending(long pending, long unknown) {
        return pending + unknown;
    }

    private static String fmtNumber(double value) {
        return fmtNumber(Double.valueOf(value));
    }

    private static String fmtNumber(Number value) {
        if (value == null) return EMPTY;
        NumberFormat fmt = NumberFormat.getNumberInstance(LOCALE);
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        return fmt.format(value);
    }

    private static String fmtCurrency(double value) {
        return fmtCurrency(Double.valueOf(value));
    }

    private static String fmtCurrency(Number value) {
        if (value == null) return EMPTY;
        NumberFormat fmt = NumberFormat.getCurrencyInstance(LOCALE);
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(2);
        return fmt.format(value);
    }

    private static String fmtPercent(double value) {
        NumberFormat fmt = NumberFormat.getNumberInstance(LOCALE);
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        return fmt.format(value) + " %";
    }

    private static String fmtInteger(long value) {
        NumberFormat fmt = NumberFormat.getIntegerInstance(LOCALE);
        return fmt.format(value);
    }

    private static String signedNumber(double value) {
        String sign = value > 0d ? "+" : value < 0d ? "-" : "";
        return sign + fmtNumber(Math.abs(value));
    }

    @FunctionalInterface
    private interface MetricExtractor {
        Double extract(WorkforceGestorDto row);
    }

    private record MetricLeader(String gestor, double value) {}

    private record Concentration(List<String> leaders, double share) {}
}
