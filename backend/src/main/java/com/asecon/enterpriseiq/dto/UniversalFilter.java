package com.asecon.enterpriseiq.dto;

public class UniversalFilter {
    private String column;
    private String op; // eq | contains | year_eq | gt | gte | lt | lte
    private String value;

    public String getColumn() { return column; }
    public void setColumn(String column) { this.column = column; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}

