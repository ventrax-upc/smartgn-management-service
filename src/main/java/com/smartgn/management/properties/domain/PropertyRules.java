package com.smartgn.management.properties.domain;

import com.smartgn.management.shared.domain.DomainException;
import com.smartgn.management.shared.domain.ManagementModels.Property;
import java.util.Locale;
import java.util.Set;

/** Invariants for property identity, active use and descriptive data. */
public final class PropertyRules {
    private PropertyRules() {}
    public static void requireActive(Property property) {
        if(!property.active()) throw new DomainException("CONFLICT","Property is inactive");
    }
    public static void requireVersion(long actual,long requested) {
        if(actual!=requested) throw new DomainException("CONFLICT","The resource version changed; reload it before updating");
    }
    public static String propertyType(String input) {
        String value=text(input,30,"propertyType").toUpperCase(Locale.ROOT);
        if(!Set.of("HOUSE","COMMERCIAL","BUILDING").contains(value))
            throw new DomainException("VALIDATION","propertyType must be HOUSE, COMMERCIAL or BUILDING");
        return value;
    }
    public static String text(String value,int max,String field) {
        if(value==null||value.isBlank()||value.strip().length()>max)
            throw new DomainException("VALIDATION",field+" is required and must be at most "+max+" characters");
        return value.strip();
    }
}
