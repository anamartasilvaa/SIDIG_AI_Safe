package pt.isep.psoft.aisafe.maintenance.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "aircraft_reference")
public class AircraftReference {
    @Id
    @Column(name = "registration_number")
    private String registrationNumber;
    @Column(name = "model_name", nullable = false)
    private String modelName;
    @Column(nullable = false)
    private String status;

    protected AircraftReference() {}

    public AircraftReference(String registrationNumber, String modelName, String status) {
        this.registrationNumber = registrationNumber;
        this.modelName = modelName;
        this.status = status;
    }

    public void update(String modelName, String status) {
        if (modelName != null) this.modelName = modelName;
        if (status != null) this.status = status;
    }

    public String getRegistrationNumber() { return registrationNumber; }
    public String getModelName() { return modelName; }
    public String getStatus() { return status; }
}
