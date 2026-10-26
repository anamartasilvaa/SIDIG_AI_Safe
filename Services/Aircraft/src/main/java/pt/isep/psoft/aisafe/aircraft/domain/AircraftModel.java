package pt.isep.psoft.aisafe.aircraft.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.util.Assert;

@Entity
public class AircraftModel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;
    @Version
    private Long version;
    @Getter
    @Embedded
    @AttributeOverride(name = "name", column = @Column(name = "model_name", unique = true, nullable = false))
    private ModelName modelName;
    @Getter
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Manufacturer manufacturer;
    @Getter
    @Column(nullable = false)
    private Integer defaultSeatingCapacity;
    @Getter
    @Column(nullable = false)
    private Double fuelCapacity;
    @Getter
    @Column(nullable = false)
    private Double maximumRange;
    @Getter
    @Column(nullable = false)
    private Double cruisingSpeed;
    @Getter
    @Column(nullable = false)
    private Double fuelConsumptionPerHour;
    @Getter
    @Column(nullable = true)
    private String modelPhotoUrl;
    @Getter
    @Column(nullable = true)
    private String operatingHoursRange;
    @Getter
    @Column(nullable = true)
    private String seatingConfiguration;
    @Getter
    @Column(nullable = true)
    private String engineType;

    protected AircraftModel() {}

    // CONSTRUTOR COMPLETO (Usado no Serviço)
    public AircraftModel(ModelName modelName, Manufacturer manufacturer, Integer defaultSeatingCapacity,
                         Double fuelCapacity, Double maximumRange, Double cruisingSpeed, Double fuelConsumptionPerHour,
                         String modelPhotoUrl, String seatingConfiguration, String operatingHoursRange, String engineType) {

        // --- VALIDAÇÕES OBRIGATÓRIAS ---
        Assert.notNull(modelName, "Model name is required.");
        Assert.notNull(manufacturer, "Manufacturer is required.");
        Assert.isTrue(defaultSeatingCapacity != null && defaultSeatingCapacity > 0, "Seating capacity must be greater than 0.");
        Assert.isTrue(fuelCapacity != null && fuelCapacity > 0, "Fuel capacity must be greater than 0.");
        Assert.isTrue(maximumRange != null && maximumRange > 0, "Range must be greater than 0.");
        Assert.isTrue(cruisingSpeed != null && cruisingSpeed > 0, "Cruising speed must be greater than 0.");
        Assert.isTrue(fuelConsumptionPerHour != null && fuelConsumptionPerHour >= 0, "Fuel consumption cannot be negative.");
        // -------------------------------

        this.modelName = modelName;
        this.manufacturer = manufacturer;
        this.defaultSeatingCapacity = defaultSeatingCapacity;
        this.fuelCapacity = fuelCapacity;
        this.maximumRange = maximumRange;
        this.cruisingSpeed = cruisingSpeed;
        this.fuelConsumptionPerHour = fuelConsumptionPerHour;
        this.modelPhotoUrl = modelPhotoUrl;
        this.seatingConfiguration = seatingConfiguration;
        this.operatingHoursRange = operatingHoursRange;
        this.engineType = engineType;
    }


    public AircraftModel(ModelName modelName, Manufacturer manufacturer, Integer defaultSeatingCapacity,
                         Double fuelCapacity, Double maximumRange, Double cruisingSpeed,
                         String modelPhotoUrl, String seatingConfiguration, String operatingHoursRange, String engineType) {
        this(modelName, manufacturer, defaultSeatingCapacity, fuelCapacity, maximumRange, cruisingSpeed, 0.0,
                modelPhotoUrl, seatingConfiguration, operatingHoursRange, engineType);
    }

    public void updateSpecifications(Integer cap, Double fuel, Double range, Double speed, String config, String hours) {
        Assert.isTrue(cap > 0, "Seating capacity must be > 0.");
        this.defaultSeatingCapacity = cap;
        this.fuelCapacity = fuel;
        this.maximumRange = range;
        this.cruisingSpeed = speed;
        this.seatingConfiguration = config;
        this.operatingHoursRange = hours;
    }
    public void updateImage(String url) { this.modelPhotoUrl = url; }
}
