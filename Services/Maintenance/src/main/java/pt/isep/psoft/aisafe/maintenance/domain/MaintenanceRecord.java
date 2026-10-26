package pt.isep.psoft.aisafe.maintenance.domain;

import jakarta.persistence.*;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;

@Entity
public class MaintenanceRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;
    @Column(nullable = false, unique = true, updatable = false)
    private String globalId = UUID.randomUUID().toString();
    @Column(nullable = false)
    private long revision;
    @Column(nullable = false)
    private Instant lastModified = Instant.now();

    @Version
    private Long version;

    @Column(nullable = false)
    private String registrationNumber;

    @Column(nullable = false)
    private String modelName;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private Integer expectedDuration;

    private String completionNotes;

    @Column(nullable = false)
    private Double cost;

    private LocalDateTime completionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenanceRecordStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComponentCategory componentCategory;

    @Column(nullable = false)
    private String templateName;

    protected MaintenanceRecord() {
    }

    public MaintenanceRecord(String registrationNumber, String modelName, String description,
                             Integer expectedDuration, ComponentCategory componentCategory,
                             LocalDateTime startDate, Double cost, String templateName) {
        Assert.hasText(registrationNumber, "Registration number is required.");
        Assert.hasText(modelName, "Model name is required.");
        Assert.hasText(description, "Description is required.");
        Assert.notNull(expectedDuration, "Expected duration is required.");
        Assert.notNull(componentCategory, "Component category is required.");
        Assert.notNull(startDate, "Start date is required.");
        Assert.notNull(cost, "Cost is required.");
        Assert.isTrue(cost >= 0, "Cost cannot be negative.");
        this.registrationNumber = registrationNumber;
        this.modelName = modelName;
        this.description = description;
        this.expectedDuration = expectedDuration;
        this.componentCategory = componentCategory;
        this.startDate = startDate;
        this.cost = cost;
        this.templateName = templateName;
        this.status = MaintenanceRecordStatus.SCHEDULED;
    }

    public static MaintenanceRecord fromReplica(String globalId, String registrationNumber, String modelName,
                                                String templateName, String description, LocalDateTime startDate,
                                                Integer expectedDuration, String completionNotes, Double cost,
                                                LocalDateTime completionDate, MaintenanceRecordStatus status,
                                                ComponentCategory componentCategory, long revision,
                                                Instant lastModified) {
        MaintenanceRecord record = new MaintenanceRecord(registrationNumber, modelName, description,
                expectedDuration, componentCategory, startDate, cost, templateName);
        record.globalId = globalId;
        record.completionNotes = completionNotes;
        record.completionDate = completionDate;
        record.status = status;
        record.revision = revision;
        record.lastModified = lastModified;
        return record;
    }

    public boolean mergeReplica(MaintenanceRecordStatus incomingStatus, String incomingNotes,
                                LocalDateTime incomingCompletionDate, long incomingRevision,
                                Instant incomingLastModified) {
        if (incomingRevision <= revision) {
            return false;
        }
        status = incomingStatus;
        completionNotes = incomingNotes;
        completionDate = incomingCompletionDate;
        revision = incomingRevision;
        lastModified = incomingLastModified;
        return true;
    }

    public void complete(String completionNotes) {
        Assert.hasText(completionNotes, "Completion notes are required.");
        this.status = MaintenanceRecordStatus.COMPLETED;
        this.completionNotes = completionNotes;
        this.completionDate = LocalDateTime.now();
        revision++; lastModified = Instant.now();
    }

    public Long getPk() {
        return pk;
    }

    public Long getVersion() {
        return version;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public String getModelName() {
        return modelName;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getStartDate() {
        return startDate;
    }

    public Integer getExpectedDuration() {
        return expectedDuration;
    }

    public String getCompletionNotes() {
        return completionNotes;
    }

    public Double getCost() {
        return cost;
    }

    public LocalDateTime getCompletionDate() {
        return completionDate;
    }

    public MaintenanceRecordStatus getStatus() {
        return status;
    }

    public ComponentCategory getComponentCategory() {
        return componentCategory;
    }

    public String getTemplateName() {
        return templateName;
    }
    public String getGlobalId() { return globalId; }
    public long getRevision() { return revision; }
    public Instant getLastModified() { return lastModified; }
}
