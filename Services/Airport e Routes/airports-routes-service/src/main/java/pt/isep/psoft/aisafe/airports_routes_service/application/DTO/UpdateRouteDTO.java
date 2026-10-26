package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import jakarta.validation.constraints.Min;
import java.time.Instant;

public record UpdateRouteDTO(
        @Min(value = 1, message = "Flight time must be positive")
        Integer estimatedFlightTime,

        @Min(value = 1, message = "Range must be positive")
        Double minimumRange,

        @Min(value = 1, message = "Capacity must be positive")
        Integer minimumCapacity, Instant lastModified, String originNodeId
) {
    public UpdateRouteDTO(Integer a,Double b,Integer c){this(a,b,c,null,null);}
}