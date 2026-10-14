package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import java.util.List;

public record AvailabilityDTO(
        String registrationNumber,
        String dateTime,
        boolean available,
        String aircraftStatus,
        String conflictingFlightId,
        List<String> reasons,
        List<String> unreachablePeers
) {}
