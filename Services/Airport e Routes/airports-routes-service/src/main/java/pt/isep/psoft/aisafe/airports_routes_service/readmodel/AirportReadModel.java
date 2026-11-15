package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CQRS, MODELO DE LEITURA dos aeroportos.
 *
 * Vista desnormalizada, independente do agregado Airport (modelo de escrita): guarda a vista
 * completa ja pronta a devolver (viewJson) e as colunas usadas nas pesquisas.
 * So e escrita pelo AirportProjector.
 */
@Entity
@Table(name = "airport_read_model")
public class AirportReadModel {

    @Id
    private String iataCode;
    private String name;
    private String city;
    private String country;
    private String status;
    private Instant lastModified;
    private String originNodeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String viewJson;

    protected AirportReadModel() {
    }

    public AirportReadModel(String iataCode, String name, String city, String country, String status,
                            Instant lastModified, String originNodeId, String viewJson) {
        this.iataCode = iataCode;
        this.name = name;
        this.city = city;
        this.country = country;
        this.status = status;
        this.lastModified = lastModified;
        this.originNodeId = originNodeId;
        this.viewJson = viewJson;
    }

    public String getIataCode() { return iataCode; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getStatus() { return status; }
    public Instant getLastModified() { return lastModified; }
    public String getOriginNodeId() { return originNodeId; }
    public String getViewJson() { return viewJson; }
}
