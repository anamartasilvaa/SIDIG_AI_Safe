package pt.isep.psoft.aisafe.flight_operations.application;

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

    // Removemos RouteRepository e AircraftRepository, pois cada serviço tem a sua base de dados isolada.
    // Mais tarde, se precisares de validar dados de aviões ou rotas, farás pedidos HTTP aos teus colegas.
    public ScheduledFlightService(ScheduledFlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @Transactional
    public ScheduledFlightViewDTO scheduleFlight(CreateScheduledFlightDTO dto) {
        // Numa fase inicial isolada, criamos o voo diretamente com os IDs recebidos no DTO.
        // As validações cruzadas (capacidade, estado do aeroporto, etc.) serão feitas via REST client futuramente.

        LocalDateTime dateTime = LocalDateTime.parse(dto.scheduledDateTime());

        ScheduledFlight flight = new ScheduledFlight(
                dto.routeId(),
                dto.registrationNumber(),
                dateTime
        );

        ScheduledFlight savedFlight = flightRepository.save(flight);

        return new ScheduledFlightViewDTO(
                savedFlight.getPk(),
                savedFlight.getRouteId(),
                savedFlight.getAircraftRegistration(),
                savedFlight.getScheduledDateTime().toString(),
                savedFlight.getStatus().name()
        );
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
}