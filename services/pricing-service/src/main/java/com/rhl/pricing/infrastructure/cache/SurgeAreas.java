package com.rhl.pricing.infrastructure.cache;

import com.rhl.pricing.PricingServiceProperties;
import com.uber.h3core.H3Core;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** H3 cells used as surge areas (README §4.1). Thread-safe; loads the native library once. */
@Component
public class SurgeAreas {

    private final H3Core h3;
    private final int resolution;
    private final int ringSize;

    public SurgeAreas(PricingServiceProperties properties) {
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load the H3 native library", e);
        }
        this.resolution = properties.surge().h3Resolution();
        this.ringSize = properties.surge().ringSize();
    }

    public String cellOf(double latitude, double longitude) {
        return h3.latLngToCellAddress(latitude, longitude, resolution);
    }

    /** The cell and its neighbours within {@code ringSize}: the area a surge is computed over. */
    public List<String> areaAround(String cell) {
        return h3.gridDisk(cell, ringSize);
    }
}
