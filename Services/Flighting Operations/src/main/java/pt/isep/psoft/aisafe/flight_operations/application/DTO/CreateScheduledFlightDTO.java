package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import jakarta.validation.constraints.NotBlank;

public record CreateScheduledFlightDTO(
        @NotBlank String routeId,
        @NotBlank String registrationNumber,
        @NotBlank String scheduledDateTime
) {}