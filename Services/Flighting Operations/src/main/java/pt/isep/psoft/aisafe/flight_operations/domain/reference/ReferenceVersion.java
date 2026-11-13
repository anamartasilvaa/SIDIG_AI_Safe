package pt.isep.psoft.aisafe.flight_operations.domain.reference;

import java.time.Instant;
final class ReferenceVersion {

    private ReferenceVersion() {}

    static boolean isNewer(long incomingRevision, Instant incomingAt, long localRevision, Instant localAt) {
        if (incomingRevision > 0 && localRevision > 0 && incomingRevision != localRevision) {
            return incomingRevision > localRevision;
        }
        if (incomingAt == null) {
            return false;
        }
        return localAt == null || incomingAt.isAfter(localAt);
    }
}