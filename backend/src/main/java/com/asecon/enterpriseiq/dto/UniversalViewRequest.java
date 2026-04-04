package com.asecon.enterpriseiq.dto;

import java.util.List;

public class UniversalViewRequest {
    private String name;
    private String type; // TIME_SERIES | CATEGORY_BAR | KPI_CARDS | SCATTER | HEATMAP | PIVOT_MONTHLY
    private String dateColumn;
    private String valueColumn;
    private String categoryColumn;
    private String xColumn;
    private String yColumn;
    private String aggregation; // sum | avg
    // Legacy single-filter fields (keep for backward compatibility)
    private String filterColumn;
    private String filterValue;
    // New: multiple filters (AND)
    private List<UniversalFilter> filters;
    private Integer topN;
    private Integer maxPoints;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDateColumn() { return dateColumn; }
    public void setDateColumn(String dateColumn) { this.dateColumn = dateColumn; }
    public String getValueColumn() { return valueColumn; }
    public void setValueColumn(String valueColumn) { this.valueColumn = valueColumn; }
    public String getCategoryColumn() { return categoryColumn; }
    public void setCategoryColumn(String categoryColumn) { this.categoryColumn = categoryColumn; }
    public String getXColumn() { return xColumn; }
    public void setXColumn(String xColumn) { this.xColumn = xColumn; }
    public String getYColumn() { return yColumn; }
    public void setYColumn(String yColumn) { this.yColumn = yColumn; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    public String getFilterColumn() { return filterColumn; }
    public void setFilterColumn(String filterColumn) { this.filterColumn = filterColumn; }
    public String getFilterValue() { return filterValue; }
    public void setFilterValue(String filterValue) { this.filterValue = filterValue; }
    public List<UniversalFilter> getFilters() { return filters; }
    public void setFilters(List<UniversalFilter> filters) { this.filters = filters; }
    public Integer getTopN() { return topN; }
    public void setTopN(Integer topN) { this.topN = topN; }
    public Integer getMaxPoints() { return maxPoints; }
    public void setMaxPoints(Integer maxPoints) { this.maxPoints = maxPoints; }
}
