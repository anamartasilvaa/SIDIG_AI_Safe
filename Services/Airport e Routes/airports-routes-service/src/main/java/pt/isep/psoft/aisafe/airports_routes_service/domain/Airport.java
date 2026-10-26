package pt.isep.psoft.aisafe.airports_routes_service.domain;

import jakarta.persistence.*;
import org.springframework.util.Assert;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportStatus;
import pt.isep.psoft.aisafe.airports_routes_service.application.Coordinates;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Instant;

@Entity
public class Airport {
    @Column(nullable=false) private Instant lastModified = Instant.now();
    @Column(nullable=false) private String originNodeId = "local";


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Version
    private Long version;


    @Embedded
    @AttributeOverride(name = "code", column = @Column(name = "iata_code", unique = true, nullable = false))
    private IATACode iataCode;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AirportType type;

    @Embedded
    private Coordinates coordinates;


    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "airport_pk")
    private List<Runway> runways = new ArrayList<>();

    @Column(nullable = true)
    private String imageUrl;

    @Column(nullable = true)
    private String operationalHours;

    @Column(nullable = true)
    private String contactInformation;

    public void updateDetails(String operationalHours, String contactInformation) {
        if (operationalHours != null && !operationalHours.isBlank()) {
            this.operationalHours = operationalHours;
        }
        if (contactInformation != null && !contactInformation.isBlank()) {
            this.contactInformation = contactInformation;
        }
        touch();
    }
    public String getOperationalHours() { return operationalHours; }
    public String getContactInformation() { return contactInformation; }


    public void updateImage(String newImageUrl) {
        this.imageUrl = newImageUrl;
        touch();
    }

    public String getImageUrl() {
        return imageUrl;
    }


    protected Airport() {}

    public Airport(IATACode iataCode, String name, String city, String country, String timezone, AirportType type, Coordinates coordinates) {
        Assert.notNull(iataCode, "The IATA code is mandatory.");
        Assert.hasText(name, "The airport name is required.");
        Assert.hasText(city, "The city is required.");
        Assert.hasText(country, "The country is required.");
        Assert.hasText(timezone, "The timezone is required.");
        Assert.notNull(type, "The type of airport is mandatory.");
        Assert.notNull(coordinates, "Coordinates are required.");

        this.iataCode = iataCode;
        this.name = name;
        this.city = city;
        this.country = country;
        this.timezone = timezone;
        this.type = type;
        this.coordinates = coordinates;
    }


    public void addRunway(Runway runway) {
        Assert.notNull(runway, "Runway must not be null.");
        this.runways.add(runway);
    }

    public IATACode getIataCode() { return iataCode; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getTimezone() { return timezone; }
    public Coordinates getCoordinates() {return coordinates;}
    public AirportType getType() { return type; }
    public List<Runway> getRunways() { return runways; }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Airport airport = (Airport) o;
        return Objects.equals(iataCode, airport.iataCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(iataCode);
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AirportStatus status = AirportStatus.OPERATIONAL;

    public void updateStatus(AirportStatus newStatus) {
        Assert.notNull(newStatus, "Status cannot be null.");
        this.status = newStatus;
        touch();
    }
    private void touch() { lastModified=Instant.now(); }
    public Instant getLastModified(){return lastModified;} public String getOriginNodeId(){return originNodeId;}
    public void applyReplicationMetadata(Instant timestamp, String node) {
        if (timestamp != null) lastModified=timestamp; if (node != null) originNodeId=node;
    }
    public void applyReplicationData(String name, String city, String country, String timezone,
                                     AirportType type, Coordinates coordinates, AirportStatus status,
                                     List<Runway> runways, List<Facility> facilities, String imageUrl,
                                     String operationalHours, String contactInformation) {
        this.name = name; this.city = city; this.country = country; this.timezone = timezone;
        this.type = type; this.coordinates = coordinates; this.status = status;
        this.runways.clear(); if (runways != null) this.runways.addAll(runways);
        this.facilities.clear(); if (facilities != null) this.facilities.addAll(facilities);
        this.imageUrl = imageUrl; this.operationalHours = operationalHours;
        this.contactInformation = contactInformation;
    }

    public AirportStatus getStatus() { return status; }


    @ElementCollection
    private List<Facility> facilities = new ArrayList<>();

    public void addFacility(Facility facility) {
        Assert.notNull(facility, "Facility cannot be null.");
        this.facilities.add(facility);
    }

    public List<Facility> getFacilities() {
        return facilities;
    }
}