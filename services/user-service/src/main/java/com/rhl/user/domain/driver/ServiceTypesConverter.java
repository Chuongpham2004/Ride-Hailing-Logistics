package com.rhl.user.domain.driver;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** A small set of service types stored as {@code "DELIVERY,RIDE"}; empty set ↔ {@code NULL}. */
@Converter
public class ServiceTypesConverter implements AttributeConverter<Set<ServiceType>, String> {

    @Override
    public String convertToDatabaseColumn(Set<ServiceType> types) {
        return types == null || types.isEmpty() ? null
                : types.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    @Override
    public Set<ServiceType> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.noneOf(ServiceType.class);
        }
        return Arrays.stream(value.split(","))
                .map(ServiceType::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ServiceType.class)));
    }
}
