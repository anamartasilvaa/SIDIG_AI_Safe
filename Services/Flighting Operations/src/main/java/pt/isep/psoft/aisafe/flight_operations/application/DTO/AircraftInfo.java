package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import pt.isep.psoft.aisafe.flight_operations.domain.AircraftCapabilities;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AircraftInfo(
        @JsonAlias({"registration"}) String registrationNumber,
        @JsonAlias({"aircraftStatus"}) String status,
        @JsonAlias({"rangeKm", "range"}) Double maximumRange,
        @JsonAlias({"capacity", "seatingCapacity"}) Integer actualSeatingCapacity
) {
    public AircraftCapabilities toCapabilities(String requestedRegistration) {
        return new AircraftCapabilities(
                registrationNumber != null ? registrationNumber : requestedRegistration,
                status, maximumRange, actualSeatingCapacity);
    }
}
