package com.asecon.enterpriseiq.dto;

public record WorkforceStatusBreakdownDto(
    long ok,
    long no,
    long negative,
    long pending,
    long unknown
) {}
