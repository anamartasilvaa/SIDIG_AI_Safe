package pt.isep.psoft.aisafe.aircraft.application.DTO;

import java.time.LocalDate;
import java.time.Instant;

public record AircraftViewDTO(
        String registrationNumber,
        String modelName,
        LocalDate manufacturingDate,
        Integer seatingCapacity,
        String status,
        String modelPhotoUrl,
        String features,
        String engineType,
        String globalId,
        long revision,
        Instant lastModified
) {
    public AircraftViewDTO(String registrationNumber, String modelName, LocalDate manufacturingDate,
                           Integer seatingCapacity, String status, String modelPhotoUrl,
                           String features, String engineType) {
        this(registrationNumber, modelName, manufacturingDate, seatingCapacity, status,
                modelPhotoUrl, features, engineType, registrationNumber, 0, Instant.EPOCH);
    }
}
