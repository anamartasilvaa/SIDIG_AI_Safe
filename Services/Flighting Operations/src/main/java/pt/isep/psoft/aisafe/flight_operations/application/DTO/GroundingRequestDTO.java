package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import java.util.List;

public record GroundingRequestDTO(List<String> candidateRegistrationNumbers) {}
