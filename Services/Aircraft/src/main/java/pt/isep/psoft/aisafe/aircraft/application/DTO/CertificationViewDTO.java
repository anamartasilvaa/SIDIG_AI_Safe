package pt.isep.psoft.aisafe.aircraft.application.DTO;
import java.time.LocalDate;

public record CertificationViewDTO(
        String certificationNumber,
        String modelName,
        LocalDate expiryDate
) {}
