package pt.isep.psoft.aisafe.flight_operations.application.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.RouteUtilizationDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.UnicornDTO;
import pt.isep.psoft.aisafe.flight_operations.domain.*;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.AircraftMoodViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.FlightViewRepository;
import pt.isep.psoft.aisafe.flight_operations.repositories.query.RouteUtilizationViewRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class FlightQueryService {

    private final FlightViewRepository flightViews;
    private final RouteUtilizationViewRepository routeUtilization;
    private final AircraftRefRepository aircraftRefs;
    private final AircraftMoodViewRepository moods;
    private final FlightSchedulingPolicy policy;

    public FlightQueryService(FlightViewRepository flightViews, RouteUtilizationViewRepository routeUtilization,
                              AircraftRefRepository aircraftRefs, AircraftMoodViewRepository moods,
                              FlightSchedulingPolicy policy) {
        this.flightViews = flightViews;
        this.routeUtilization = routeUtilization;
        this.aircraftRefs = aircraftRefs;
        this.moods = moods;
        this.policy = policy;
    }

    public List<UnicornDTO> unicorns(String mood) {
        List<AircraftMoodView> rows;
        if (mood == null || mood.isBlank()) {
            rows = moods.findAllByOrderByRegistrationNumberAsc();
        } else {
            AircraftMood wanted;
            try {
                wanted = AircraftMood.valueOf(mood.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown mood '" + mood + "'. Use HAPPY, BUSY, GRUMPY or SLEEPING.");
            }
            rows = moods.findByMoodOrderByRegistrationNumberAsc(wanted);
        }
        return rows.stream().map(UnicornDTO::from).toList();
    }

    public Optional<ScheduledFlightViewDTO> findById(String id) {
        return flightViews.findById(FlightId.of(id).value()).map(ScheduledFlightViewDTO::from);
    }

    /** US213 */
    public List<ScheduledFlightViewDTO> findByAircraft(String registration) {
        return flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc(RegistrationNumber.of(registration).value())
                .stream().map(ScheduledFlightViewDTO::from).toList();
    }

    /** US229: pre-computed by the FlightProjector; no aggregation at query time. */
    public List<RouteUtilizationDTO> routeUtilization() {
        return routeUtilization.findByFlightCountGreaterThanOrderByFlightCountDescRouteIdAsc(0).stream()
                .map(r -> new RouteUtilizationDTO(r.getRouteId(), r.getFlightCount()))
                .toList();
    }

    /** US205 */
    public AvailabilityDTO availability(String registration, String dateTime) {
        RegistrationNumber reg = RegistrationNumber.of(registration);
        LocalDateTime when = parseDateTime(dateTime);
        List<String> reasons = new ArrayList<>();

        Optional<AircraftRef> aircraft = aircraftRefs.findById(reg.value());
        if (aircraft.isEmpty() && referenceData != null) {
            try {
                if (referenceData.ensureAircraft(reg)) {
                    aircraft = aircraftRefs.findById(reg.value());
                }
            } catch (RuntimeException e) {
            }
        }
        String aircraftStatus = aircraft.map(AircraftRef::getStatus).orElse("UNKNOWN");
        if (aircraft.isEmpty()) {
            reasons.add("Aircraft " + reg + " is not known by Flight Operations (not registered or not yet synchronised).");
        } else if (!aircraft.get().isActive()) {
            reasons.add("Aircraft " + reg + " is " + aircraftStatus + ".");
        }

        List<FlightSlot> slots = flightViews.findByRegistrationNumberOrderByScheduledDateTimeAsc(reg.value()).stream()
                .map(v -> new FlightSlot(v.getFlightId(), v.getScheduledDateTime(), FlightStatus.parse(v.getStatus())))
                .toList();
        Optional<FlightSlot> conflict = policy.findConflict(slots, when);
        conflict.ifPresent(c -> reasons.add("Conflicts with flight " + c.flightId() + " at " + c.scheduledDateTime() + "."));

        return new AvailabilityDTO(reg.value(), when.toString(), reasons.isEmpty(), aircraftStatus,
                conflict.map(FlightSlot::flightId).orElse(null), reasons, List.of());
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The date and time are required.");
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time '" + value + "'. Expected ISO-8601, e.g. 2027-03-15T10:00:00");
        }
    }

    private pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceDataService referenceData;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceData(pt.isep.psoft.aisafe.flight_operations.application.reference.ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }
}
