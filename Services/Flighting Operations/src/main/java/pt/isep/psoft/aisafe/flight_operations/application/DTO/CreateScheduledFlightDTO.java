package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import jakarta.validation.constraints.NotBlank;

public record CreateScheduledFlightDTO(
        @NotBlank String routeId,
        @NotBlank String registrationNumber,
        @NotBlank String scheduledDateTime   // ISO-8601, ex.: 2026-11-20T14:30:00
) {}
