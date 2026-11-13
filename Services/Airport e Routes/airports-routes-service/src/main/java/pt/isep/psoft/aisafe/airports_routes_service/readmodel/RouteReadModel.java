package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CQRS, MODELO DE LEITURA das rotas.
 *
 * Tabela plana e desnormalizada (inclui os codigos IATA e as coordenadas dos dois aeroportos),
 * por isso as queries nao precisam de joins com Airport nem do agregado Route.
 * So e escrita pelo RouteProjector.
 */
@Entity
@Table(name = "route_read_model")
public class RouteReadModel {

    @Id
    private String routeId;
    private String originIata;
    private String destinationIata;
    private String status;
    private Integer minimumCapacity;
    private Double minimumRange;
    private Integer estimatedFlightTime;
    private Instant lastModified;
    private String originNodeId;
    private Double originLatitude;
    private Double originLongitude;
    private Double destinationLatitude;
    private Double destinationLongitude;

    protected RouteReadModel() {
    }

    public RouteReadModel(String routeId, String originIata, String destinationIata, String status,
                          Integer minimumCapacity, Double minimumRange, Integer estimatedFlightTime,
                          Instant lastModified, String originNodeId,
                          Double originLatitude, Double originLongitude,
                          Double destinationLatitude, Double destinationLongitude) {
        this.routeId = routeId;
        this.originIata = originIata;
        this.destinationIata = destinationIata;
        this.status = status;
        this.minimumCapacity = minimumCapacity;
        this.minimumRange = minimumRange;
        this.estimatedFlightTime = estimatedFlightTime;
        this.lastModified = lastModified;
        this.originNodeId = originNodeId;
        this.originLatitude = originLatitude;
        this.originLongitude = originLongitude;
        this.destinationLatitude = destinationLatitude;
        this.destinationLongitude = destinationLongitude;
    }

    public String getRouteId() { return routeId; }
    public String getOriginIata() { return originIata; }
    public String getDestinationIata() { return destinationIata; }
    public String getStatus() { return status; }
    public Integer getMinimumCapacity() { return minimumCapacity; }
    public Double getMinimumRange() { return minimumRange; }
    public Integer getEstimatedFlightTime() { return estimatedFlightTime; }
    public Instant getLastModified() { return lastModified; }
    public String getOriginNodeId() { return originNodeId; }
    public Double getOriginLatitude() { return originLatitude; }
    public Double getOriginLongitude() { return originLongitude; }
    public Double getDestinationLatitude() { return destinationLatitude; }
    public Double getDestinationLongitude() { return destinationLongitude; }
}
