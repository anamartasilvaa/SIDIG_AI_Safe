package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import jakarta.validation.constraints.NotBlank;

public record ChangeFlightStatusDTO(@NotBlank String status) {}
