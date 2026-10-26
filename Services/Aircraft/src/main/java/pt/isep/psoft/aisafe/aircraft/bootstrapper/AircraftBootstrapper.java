package pt.isep.psoft.aisafe.aircraft.bootstrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.aircraft.application.AircraftService;
import pt.isep.psoft.aisafe.aircraft.application.DTO.RegisterAircraftDTO;
import pt.isep.psoft.aisafe.aircraft.application.DTO.RegisterAircraftModelDTO;
import pt.isep.psoft.aisafe.aircraft.domain.AircraftStatus;
import pt.isep.psoft.aisafe.aircraft.domain.ModelName;
import pt.isep.psoft.aisafe.aircraft.domain.RegistrationNumber;
import pt.isep.psoft.aisafe.aircraft.repositories.AircraftModelRepository;
import pt.isep.psoft.aisafe.aircraft.repositories.AircraftRepository;

import java.time.LocalDate;
@Component
@Order(10)
public class AircraftBootstrapper implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AircraftBootstrapper.class);
    private static final String MODEL = "A320";

    private final AircraftService service;
    private final AircraftRepository aircraftRepository;
    private final AircraftModelRepository modelRepository;

    public AircraftBootstrapper(AircraftService service, AircraftRepository aircraftRepository,
                                AircraftModelRepository modelRepository) {
        this.service = service;
        this.aircraftRepository = aircraftRepository;
        this.modelRepository = modelRepository;
    }

    @Override
    public void run(String... args) {
        if (modelRepository.findByModelName(new ModelName(MODEL)).isEmpty()) {
            service.registerModel(new RegisterAircraftModelDTO(MODEL, "AIRBUS", 180, 24210.0, 6100.0, 830.0,
                    2500.0, null, "3-3", "0-60000", "CFM56"));
        }
        seed("CS-TTA", 180, AircraftStatus.ACTIVE);
        seed("CS-TTB", 120, AircraftStatus.ACTIVE);
        seed("CS-TTM", 180, AircraftStatus.UNDER_MAINTENANCE);
        log.info("Bootstrap: avioes CS-TTA, CS-TTB e CS-TTM disponiveis (modelo {})", MODEL);
    }

    private void seed(String registration, int seats, AircraftStatus status) {
        RegistrationNumber reg = new RegistrationNumber(registration);
        if (aircraftRepository.findByRegistrationNumber(reg).isPresent()) {
            return;
        }
        service.registerAircraft(new RegisterAircraftDTO(registration, MODEL, LocalDate.of(2018, 5, 1), seats, "WiFi"));
        if (status != AircraftStatus.ACTIVE) {
            aircraftRepository.findByRegistrationNumber(reg).ifPresent(a -> {
                a.updateStatus(status);
                aircraftRepository.save(a);
            });
        }
    }
}
