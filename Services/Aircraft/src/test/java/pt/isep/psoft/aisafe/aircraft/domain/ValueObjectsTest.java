package pt.isep.psoft.aisafe.aircraft.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueObjectsTest {
    @Test
    void validRegistrationIsAccepted() {
        assertEquals("CS-TTA", new RegistrationNumber("CS-TTA").number());
        assertEquals(new ModelName("A320"), new ModelName("A320"));
    }

    @Test
    void invalidRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber("invalid"));
        assertThrows(IllegalArgumentException.class, () -> new RegistrationNumber(" "));
        assertThrows(IllegalArgumentException.class, () -> new ModelName(" "));
    }
}
