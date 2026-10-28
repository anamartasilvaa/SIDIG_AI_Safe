package pt.isep.psoft.aisafe.airports_routes_service.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.UpdateAirportDetailsDTO;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirportValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void testUpdateAirportDetailsLengthValid() {
        UpdateAirportDetailsDTO dto = new UpdateAirportDetailsDTO("08:00 - 22:00", "Contact: info@airport.com");
        Set<ConstraintViolation<UpdateAirportDetailsDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testUpdateAirportDetailsExceedsSize() {
        String longText = "A".repeat(150); // Máximo permitido é 100 para operationalHours
        UpdateAirportDetailsDTO dto = new UpdateAirportDetailsDTO(longText, "Valid contact");
        Set<ConstraintViolation<UpdateAirportDetailsDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
    }
}