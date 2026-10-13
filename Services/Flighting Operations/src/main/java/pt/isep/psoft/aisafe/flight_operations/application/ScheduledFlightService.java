package pt.isep.psoft.aisafe.flight_operations.application;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.domain.ScheduledFlight;
import pt.isep.psoft.aisafe.flight_operations.repositories.ScheduledFlightRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduledFlightService {

    private final ScheduledFlightRepository flightRepository;

    public ScheduledFlightService(ScheduledFlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @org.springframework.beans.factory.annotation.Value("${server.port:8080}")
    private String serverPort;

    @Transactional(readOnly = true)
    public ScheduledFlightViewDTO getScheduledFlightById(Long id) {
        var flight = flightRepository.findById(id);
        if (flight.isPresent()) {
            ScheduledFlight f = flight.get();
            return new ScheduledFlightViewDTO(
                    f.getPk(),
                    f.getRouteId(),
                    f.getAircraftRegistration(),
                    f.getScheduledDateTime().toString(),
                    f.getStatus().name()
            );
        }

        List<String> allPossiblePorts = List.of("8080", "8081", "8082");

        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

        for (String port : allPossiblePorts) {
            if (port.equals(serverPort)) {
                continue;
            }

            try {
                String url = "http://localhost:" + port + "/api/scheduled-flights/" + id;
                ResponseEntity<ScheduledFlightViewDTO> response = restTemplate.getForEntity(url, ScheduledFlightViewDTO.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody(); // Encontrou na réplica vizinha!
                }
            } catch (Exception e) {
            }
        }

        return null;
    }

    @Transactional(readOnly = true)
    public List<ScheduledFlightViewDTO> getScheduledFlightsByAircraft(String registrationNumber) {
        String regNum = registrationNumber.trim().toUpperCase();
        return flightRepository.findByAircraftRegistration(regNum).stream()
                .map(f -> new ScheduledFlightViewDTO(
                        f.getPk(),
                        f.getRouteId(),
                        f.getAircraftRegistration(),
                        f.getScheduledDateTime().toString(),
                        f.getStatus().name()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RouteUtilizationDTO> getRouteUtilizationReport() {
        return flightRepository.countFlightsGroupedByRoute().stream()
                .map(obj -> new RouteUtilizationDTO((String) obj[0], (Long) obj[1]))
                .collect(Collectors.toList());
    }

    public ScheduledFlightViewDTO scheduleFlight(@Valid CreateScheduledFlightDTO dto) {
        ScheduledFlight flight = new ScheduledFlight(
                dto.routeId(),
                dto.registrationNumber().trim().toUpperCase(),
                LocalDateTime.parse(dto.scheduledDateTime())
        );

        flightRepository.save(flight);

        return new ScheduledFlightViewDTO(
                flight.getPk(),
                flight.getRouteId(),
                flight.getAircraftRegistration(),
                flight.getScheduledDateTime().toString(),
                flight.getStatus().name()
        );
    }
}