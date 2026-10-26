package pt.isep.psoft.aisafe.maintenance.application.DTO;

public record LowStockAlertDTO(String partNumber, String name, Integer currentStock, Integer minimumThreshold, String severity) {
}
