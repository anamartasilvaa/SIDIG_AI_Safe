package pt.isep.psoft.aisafe.airports_routes_service.bootstrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.FacilityDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RegisterAirportDTO;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RunwayDTO;

import java.util.List;

@Component
@Order(3)
public class AirportBootstrapper implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AirportBootstrapper.class);
    private final AirportService airportService;

    public AirportBootstrapper(AirportService airportService) {
        this.airportService = airportService;
    }

    @Override
    public void run(String... args) {
        registerAirport(new RegisterAirportDTO(
                "OPO", "Aeroporto Francisco Sa Carneiro", "Porto", "Portugal", "Europe/Lisbon",
                "INTERNATIONAL", 41.2356, -8.6781,
                List.of(new RunwayDTO("17/35", 3480.0, "North-South")),
                List.of(), null));

        registerAirport(new RegisterAirportDTO(
                "LIS", "Aeroporto Humberto Delgado", "Lisbon", "Portugal", "Europe/Lisbon",
                "INTERNATIONAL", 38.7742, -9.1342,
                List.of(new RunwayDTO("03/21", 3805.0, "North-South")),
                List.of(), null));

        registerAirport(new RegisterAirportDTO(
                "JFK", "John F. Kennedy International Airport", "New York", "USA", "America/New_York",
                "INTERNATIONAL", 40.6413, -73.7781,
                List.of(
                        new RunwayDTO("04L/22R", 3460.0, "Northeast-Southwest"),
                        new RunwayDTO("13R/31L", 4442.0, "Northwest-Southeast")),
                List.of(), null));

        registerAirport(new RegisterAirportDTO(
                "CDG", "Charles de Gaulle Airport", "Paris", "France", "Europe/Paris",
                "INTERNATIONAL", 49.0097, 2.5479,
                List.of(new RunwayDTO("09L/27R", 4200.0, "East-West")),
                List.of(new FacilityDTO("Terminal", "Terminal 1")), null));

        registerAirport(new RegisterAirportDTO(
                "LHR", "Heathrow Airport", "London", "UK", "Europe/London",
                "INTERNATIONAL", 51.4700, -0.4543,
                List.of(new RunwayDTO("09L/27R", 3902.0, "East-West")),
                List.of(new FacilityDTO("Terminal", "Terminal 5")), null));
    }

    private void registerAirport(RegisterAirportDTO dto) {
        try {
            // Seed data is local and must not start a replication chain.
            airportService.registerAirport(dto, true);
        } catch (IllegalArgumentException exception) {
            log.debug("Airport {} already exists or has invalid data", dto.iataCode(), exception);
        }
    }
}
