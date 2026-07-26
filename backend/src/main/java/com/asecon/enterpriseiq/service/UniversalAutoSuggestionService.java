package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalAutoSuggestionDto;
import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalFilter;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class UniversalAutoSuggestionService {
    private final UniversalCsvService universalCsvService;
    private final UniversalViewService universalViewService;

    public UniversalAutoSuggestionService(UniversalCsvService universalCsvService,
                                          UniversalViewService universalViewService) {
        this.universalCsvService = universalCsvService;
        this.universalViewService = universalViewService;
    }

    public List<UniversalAutoSuggestionDto> suggest(Long companyId) {
        return suggest(companyId, null);
    }

    public List<UniversalAutoSuggestionDto> suggest(Long companyId, Long importId) {
        UniversalSummaryDto summary = universalCsvService.summary(companyId, importId).orElse(null);
        if (summary == null || summary.columns() == null || summary.columns().isEmpty()) return List.of();

        List<UniversalColumnDto> cols = summary.columns();
        List<UniversalColumnDto> dateCols = cols.stream().filter(c -> "date".equalsIgnoreCase(c.detectedType())).toList();
        List<UniversalColumnDto> numberCols = cols.stream().filter(c -> "number".equalsIgnoreCase(c.detectedType())).toList();
        List<UniversalColumnDto> textCols = cols.stream().filter(c -> "text".equalsIgnoreCase(c.detectedType())).toList();

        UniversalColumnDto bestDate = dateCols.isEmpty() ? null : dateCols.get(0);
        UniversalColumnDto bestNum = pickBestNumeric(numberCols);
        UniversalColumnDto secondNum = pickSecondNumeric(numberCols, bestNum);
        UniversalColumnDto bestText = pickBestText(textCols);

        List<UniversalAutoSuggestionDto> out = new ArrayList<>();

        if (bestDate != null && bestText != null && bestNum != null) {
            UniversalViewRequest req = new UniversalViewRequest();
            req.setType("PIVOT_MONTHLY");
            req.setName("Pivote mensual: " + bestText.name());
            req.setDateColumn(bestDate.name());
            req.setCategoryColumn(bestText.name());
            req.setValueColumn(bestNum.name());
            req.setAggregation("sum");
            req.setTopN(8);
            req.setFilters(defaultYearFilter(bestDate));
            out.add(suggestion(
                "Tabla pivote (categoria x mes)",
                "Ideal para presupuestos o ventas: compara categorias por mes" + yearSuffix(bestDate) + ".",
                req,
                summary
            ));
        }

        if (bestDate != null && bestNum != null && out.size() < 2) {
            UniversalViewRequest req = new UniversalViewRequest();
            req.setType("TIME_SERIES");
            req.setName("Evolucion mensual: " + bestNum.name());
            req.setDateColumn(bestDate.name());
            req.setValueColumn(bestNum.name());
            req.setAggregation("sum");
            req.setFilters(defaultYearFilter(bestDate));
            out.add(suggestion(
                "Serie temporal (fecha a valor)",
                "Tendencia mensual de " + bestNum.name() + yearSuffix(bestDate) + ".",
                req,
                summary
            ));
        }

        if (bestText != null && bestNum != null && out.size() < 2) {
            UniversalViewRequest req = new UniversalViewRequest();
            req.setType("CATEGORY_BAR");
            req.setName("Ranking: " + bestText.name());
            req.setCategoryColumn(bestText.name());
            req.setValueColumn(bestNum.name());
            req.setAggregation("sum");
            out.add(suggestion(
                "Ranking por categoria",
                "Top categorias en " + bestText.name() + " por " + bestNum.name() + ".",
                req,
                summary
            ));
        }

        if (bestNum != null && out.size() < 2) {
            UniversalViewRequest req = new UniversalViewRequest();
            req.setType("KPI_CARDS");
            req.setName("KPIs: " + bestNum.name());
            req.setValueColumn(bestNum.name());
            req.setAggregation("sum");
            if (bestDate != null) req.setFilters(defaultYearFilter(bestDate));
            out.add(suggestion(
                "KPIs (count/sum/avg)",
                "Resumen rapido de " + bestNum.name() + (bestDate != null ? yearSuffix(bestDate) : "") + ".",
                req,
                summary
            ));
        }

        if (bestNum != null && secondNum != null && out.size() < 2) {
            UniversalViewRequest req = new UniversalViewRequest();
            req.setType("SCATTER");
            req.setName("Scatter: " + bestNum.name() + " vs " + secondNum.name());
            req.setXColumn(bestNum.name());
            req.setYColumn(secondNum.name());
            req.setMaxPoints(1200);
            if (bestDate != null) req.setFilters(defaultYearFilter(bestDate));
            out.add(suggestion(
                "Scatter (X vs Y)",
                "Relacion entre " + bestNum.name() + " y " + secondNum.name() + (bestDate != null ? yearSuffix(bestDate) : "") + ".",
                req,
                summary
            ));
        }

        if (out.size() > 2) return out.subList(0, 2);
        return out;
    }

    private UniversalAutoSuggestionDto suggestion(String title,
                                                  String description,
                                                  UniversalViewRequest request,
                                                  UniversalSummaryDto summary) {
        return new UniversalAutoSuggestionDto(
            title,
            description,
            universalViewService.canonicalizeRequest(request, summary)
        );
    }

    private static UniversalColumnDto pickBestNumeric(List<UniversalColumnDto> nums) {
        if (nums == null || nums.isEmpty()) return null;
        return nums.stream()
            .max(Comparator.comparingDouble(c -> Optional.ofNullable(c.p90()).orElse(Optional.ofNullable(c.max()).orElse(0.0))))
            .orElse(nums.get(0));
    }

    private static UniversalColumnDto pickSecondNumeric(List<UniversalColumnDto> nums, UniversalColumnDto best) {
        if (nums == null || nums.size() < 2) return null;
        for (UniversalColumnDto c : nums) {
            if (best == null || !String.valueOf(c.name()).equals(String.valueOf(best.name()))) return c;
        }
        return null;
    }

    private static UniversalColumnDto pickBestText(List<UniversalColumnDto> textCols) {
        if (textCols == null || textCols.isEmpty()) return null;
        return textCols.stream()
            .filter(c -> c.topValues() != null && !c.topValues().isEmpty())
            .min(Comparator.comparingLong(UniversalColumnDto::uniqueCount))
            .orElse(textCols.get(0));
    }

    private static List<UniversalFilter> defaultYearFilter(UniversalColumnDto dateCol) {
        Integer year = latestYear(dateCol);
        if (year == null) return null;
        UniversalFilter filter = new UniversalFilter();
        filter.setColumn(dateCol.name());
        filter.setOp("year_eq");
        filter.setValue(String.valueOf(year));
        return List.of(filter);
    }

    private static String yearSuffix(UniversalColumnDto dateCol) {
        Integer year = latestYear(dateCol);
        if (year == null) return "";
        return " (ano " + year + ")";
    }

    private static Integer latestYear(UniversalColumnDto dateCol) {
        if (dateCol == null) return null;
        String min = safe(dateCol.dateMin());
        String max = safe(dateCol.dateMax());
        Integer yMin = parseYear(min);
        Integer yMax = parseYear(max);
        if (yMin == null && yMax == null) return null;
        if (yMin != null && yMax != null && yMin.equals(yMax)) return yMax;
        return yMax != null ? yMax : yMin;
    }

    private static Integer parseYear(String date) {
        if (date == null || date.isBlank()) return null;
        String s = date.trim();
        if (s.length() >= 4) {
            try {
                return Integer.parseInt(s.substring(0, 4));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? null : value.trim();
    }
}
