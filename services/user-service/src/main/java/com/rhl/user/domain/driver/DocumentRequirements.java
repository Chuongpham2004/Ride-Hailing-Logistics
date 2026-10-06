package com.rhl.user.domain.driver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which documents a driver must hold (TBD-11: the list is configuration, not code). Used at
 * submission, approval (UC-01: missing or expired papers are never approved) and go-online
 * (BR-001).
 *
 * @param driverDocuments  types every driver needs
 * @param vehicleDocuments types every vehicle used for work needs
 */
public record DocumentRequirements(Set<DocumentType> driverDocuments, Set<DocumentType> vehicleDocuments) {

    public DocumentRequirements {
        if (driverDocuments.stream().anyMatch(DocumentType::isVehicleScoped)
                || vehicleDocuments.stream().anyMatch(t -> !t.isVehicleScoped())) {
            throw new IllegalArgumentException("Document types configured in the wrong scope");
        }
        driverDocuments = Set.copyOf(driverDocuments);
        vehicleDocuments = Set.copyOf(vehicleDocuments);
    }

    /** Problems blocking review of the whole profile: driver papers and papers of every active vehicle. */
    public List<String> profileProblems(List<DriverDocument> documents, List<Vehicle> vehicles, LocalDate today) {
        List<String> problems = new ArrayList<>(driverProblems(documents, today));
        List<Vehicle> active = vehicles.stream().filter(Vehicle::isActive).toList();
        if (active.isEmpty()) {
            problems.add("At least one active vehicle is required");
        }
        active.forEach(v -> problems.addAll(vehicleProblems(documents, v, today)));
        return problems;
    }

    /** Problems blocking work with {@code vehicle} today. */
    public List<String> workProblems(List<DriverDocument> documents, Vehicle vehicle, LocalDate today) {
        List<String> problems = new ArrayList<>(driverProblems(documents, today));
        if (!vehicle.isActive()) {
            problems.add("Vehicle " + vehicle.getPlateNumber() + " is not active");
        }
        problems.addAll(vehicleProblems(documents, vehicle, today));
        return problems;
    }

    private List<String> driverProblems(List<DriverDocument> documents, LocalDate today) {
        return driverDocuments.stream().sorted()
                .map(type -> check(documents, type, null, today, type.name()))
                .filter(p -> p != null)
                .toList();
    }

    private List<String> vehicleProblems(List<DriverDocument> documents, Vehicle vehicle, LocalDate today) {
        return vehicleDocuments.stream().sorted()
                .map(type -> check(documents, type, vehicle, today, type + " of " + vehicle.getPlateNumber()))
                .filter(p -> p != null)
                .toList();
    }

    private static String check(List<DriverDocument> documents, DocumentType type, Vehicle vehicle, LocalDate today,
                                String label) {
        List<DriverDocument> current = documents.stream()
                .filter(d -> d.getType() == type && d.getStatus() == DriverDocument.Status.ACTIVE)
                .filter(d -> vehicle == null || vehicle.getId().equals(d.getVehicleId()))
                .toList();
        if (current.isEmpty()) {
            return label + " is missing";
        }
        return current.stream().anyMatch(d -> d.isValidOn(today)) ? null : label + " has expired";
    }
}
