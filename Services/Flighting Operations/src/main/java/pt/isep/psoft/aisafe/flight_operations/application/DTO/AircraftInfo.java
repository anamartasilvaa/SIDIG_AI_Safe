package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Resposta do servico Aircraft & Maintenance. Ajustar nomes dos campos ao JSON dos colegas. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AircraftInfo(String registrationNumber, String status, Double rangeKm, Integer capacity) {}
