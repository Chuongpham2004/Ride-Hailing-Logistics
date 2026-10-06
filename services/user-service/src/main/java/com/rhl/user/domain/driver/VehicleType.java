package com.rhl.user.domain.driver;

import java.util.EnumSet;
import java.util.Set;

public enum VehicleType {
    MOTORBIKE(EnumSet.of(ServiceType.RIDE, ServiceType.DELIVERY)),
    CAR_4_SEAT(EnumSet.of(ServiceType.RIDE)),
    CAR_7_SEAT(EnumSet.of(ServiceType.RIDE)),
    VAN(EnumSet.of(ServiceType.DELIVERY));

    private final Set<ServiceType> supportedServices;

    VehicleType(Set<ServiceType> supportedServices) {
        this.supportedServices = supportedServices;
    }

    public boolean supports(ServiceType serviceType) {
        return supportedServices.contains(serviceType);
    }
}
