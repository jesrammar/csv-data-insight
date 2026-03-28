package com.asecon.enterpriseiq.dto;

public class DashboardMetricDto {
    private String key;
    private String label;
    private String value;
    private String tier;

    public DashboardMetricDto() {}

    public DashboardMetricDto(String key, String label, String value, String tier) {
        this.key = key;
        this.label = label;
        this.value = value;
        this.tier = tier;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
}
