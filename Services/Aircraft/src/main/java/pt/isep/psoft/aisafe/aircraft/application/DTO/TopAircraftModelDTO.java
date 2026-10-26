package pt.isep.psoft.aisafe.aircraft.application.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopAircraftModelDTO(
        String modelName,
        Double totalFlightHours,
        Long totalAssignments
) {
    public TopAircraftModelDTO(String modelName, Double totalFlightHours) {
        this(modelName, totalFlightHours, null);
    }
    public TopAircraftModelDTO(String modelName, Long totalAssignments) {
        this(modelName, null, totalAssignments);
    }
    public TopAircraftModelDTO(String modelName, Integer totalAssignments) {
        this(modelName, null, totalAssignments == null ? null : totalAssignments.longValue());
    }
}
