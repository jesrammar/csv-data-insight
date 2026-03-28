package com.asecon.enterpriseiq.model;

public enum Plan {
    BRONZE,
    GOLD,
    PLATINUM

    ;

    public boolean isAtLeast(Plan other) {
        if (other == null) return true;
        return this.ordinal() >= other.ordinal();
    }
}
