package pt.isep.psoft.aisafe.flight_operations.application.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.flight_operations.domain.reference.AircraftRef;
import pt.isep.psoft.aisafe.flight_operations.repositories.AircraftRefRepository;

import java.util.Locale;
import java.util.Optional;

/**
 * Applies the Aircraft Service events to the local copy (aircraft_ref).
 * - newer changes only (revision, then time): old, repeated or out-of-order events are ignored;
 * - row lock on the aircraft, so it is serialised with schedules and grounding on this replica.
 */
@Service
public class AircraftReferenceService {

    private static final Logger log = LoggerFactory.getLogger(AircraftReferenceService.class);

    private final AircraftRefRepository aircraftRefs;

    public AircraftReferenceService(AircraftRefRepository aircraftRefs) {
        this.aircraftRefs = aircraftRefs;
    }

    /** @return true if the local copy changed. */
    @Transactional
    public boolean apply(AircraftChange change) {
        String registration = change.registrationNumber().trim().toUpperCase(Locale.ROOT);
        Optional<AircraftRef> existing = aircraftRefs.findForUpdate(registration);

        boolean changed;
        if (existing.isPresent()) {
            changed = existing.get().applyIfNewer(change.modelCode(), change.status(), change.maximumRange(),
                    change.seatingCapacity(), change.revision(), change.changedAt());
            if (!changed) {
                log.debug("Aircraft {}: change ignored (not newer than the local copy).", registration);
            }
        } else {
            // First time this aircraft is seen. Without a status, it is assumed ACTIVE
            // (same rule as the REST fallback); a later status event corrects it.
            String status = change.hasStatus() ? change.status() : AircraftRef.ACTIVE;
            aircraftRefs.save(AircraftRef.create(registration, change.modelCode(), status,
                    change.maximumRange(), change.seatingCapacity(), change.revision(), change.changedAt()));
            log.info("Aircraft {} added to the local references (status {}).", registration, status);
            changed = true;
        }
        return changed;
    }

    /** @return true if the aircraft is known locally and is not ACTIVE. */
    @Transactional
    public boolean isGrounded(String registration) {
        return aircraftRefs.findById(registration.trim().toUpperCase(Locale.ROOT))
                .map(ref -> !ref.isActive())
                .orElse(false);
    }
}
