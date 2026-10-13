package pt.isep.psoft.aisafe.flight_operations.application.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Resposta do servico Airports & Routes. Ajustar nomes dos campos ao JSON dos colegas. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteInfo(String routeId, Boolean active, Double distanceKm, Integer minCapacity) {}
