package pt.isep.psoft.aisafe.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import pt.isep.psoft.aisafe.application.DTO.CreateScheduledFlightDTO;
import pt.isep.psoft.aisafe.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.domain.*;
import pt.isep.psoft.aisafe.repositories.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduledFlightService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduledFlightService.class);

    private final ScheduledFlightRepository flightRepository;
    private final RouteRepository routeRepository;
    private final AircraftRepository aircraftRepository;

    public ScheduledFlightService(ScheduledFlightRepository flightRepository,
                                  RouteRepository routeRepository,
                                  AircraftRepository aircraftRepository) {
        this.flightRepository = flightRepository;
        this.routeRepository = routeRepository;
        this.aircraftRepository = aircraftRepository;
    }

    @Transactional
    public ScheduledFlightViewDTO scheduleFlight(CreateScheduledFlightDTO dto) {
        audit("CREATE", dto.routeId());
        Route route = routeRepository.findByRouteId(new RouteId(dto.routeId()))
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + dto.routeId()));

        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(dto.registrationNumber()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + dto.registrationNumber()));

        if (aircraft.getStatus() != AircraftStatus.ACTIVE) {
            throw new IllegalStateException("Aircraft is not available. Status: " + aircraft.getStatus());
        }

        if (route.getOrigin().getStatus() != AirportStatus.OPERATIONAL ||
                route.getDestination().getStatus() != AirportStatus.OPERATIONAL) {
            throw new IllegalStateException("One of the airports is not operational.");
        }


        if (aircraft.getActualSeatingCapacity() < route.getMinimumCapacity()) {
            throw new IllegalArgumentException("Aircraft does not meet route requirements: insufficient seating capacity.");
        }

        if (aircraft.getAircraftModel().getMaximumRange() < route.getMinimumRange()) {
            throw new IllegalArgumentException("Aircraft does not meet route requirements: insufficient range.");
        }

        // ------------------------------------------------------------------

        LocalDateTime dateTime = LocalDateTime.parse(dto.scheduledDateTime());
        ScheduledFlight flight = new ScheduledFlight(route, aircraft, dateTime);
        ScheduledFlight savedFlight = flightRepository.save(flight);

        return new ScheduledFlightViewDTO(
                savedFlight.getPk(),
                savedFlight.getRoute().getRouteId().id(),
                savedFlight.getAircraft().getRegistrationNumber().number(),
                savedFlight.getScheduledDateTime().toString(),
                savedFlight.getStatus().name()
        );
    }

    @Transactional(readOnly = true)
    public List<ScheduledFlightViewDTO> getScheduledFlightsByAircraft(String registrationNumber) {
        RegistrationNumber regNum = new RegistrationNumber(registrationNumber.trim().toUpperCase());
        return flightRepository.findByAircraft_RegistrationNumber(regNum).stream()
                .map(f -> new ScheduledFlightViewDTO(
                        f.getPk(),
                        f.getRoute().getRouteId().id(),
                        f.getAircraft().getRegistrationNumber().number(),
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

    private void audit(String action, Object id) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        String username = principal instanceof org.springframework.security.core.userdetails.UserDetails
                ? ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername()
                : principal instanceof String ? (String) principal : null;
        if (username == null || username.isBlank()) username = "SYSTEM";
        logger.info("AUDIT - User: {} - Action: {} - Target ID: {}", username, action, id);
    }
}