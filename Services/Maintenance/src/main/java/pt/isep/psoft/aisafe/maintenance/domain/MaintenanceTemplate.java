package pt.isep.psoft.aisafe.maintenance.domain;

import jakarta.persistence.*;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

@Entity
public class MaintenanceTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;
    @Column(nullable = false, unique = true, updatable = false)
    private String globalId = UUID.randomUUID().toString();
    @Column(nullable = false)
    private long revision;
    @Column(nullable = false)
    private Instant lastModified = Instant.now();

    @Column(unique = true, nullable = false)
    private String templateName;

    private String templateType;
    private Double flightHoursInterval;
    private Integer calendarDaysInterval;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "checklist_pk")
    private Checklist checklist;

    @ElementCollection
    @CollectionTable(name = "template_applies_to_model_names", joinColumns = @JoinColumn(name = "template_pk"))
    @Column(name = "model_name")
    private List<String> appliesToModelNames = new ArrayList<>();

    protected MaintenanceTemplate() {
    }

    public MaintenanceTemplate(String templateName, String templateType, Double flightHoursInterval,
                               Integer calendarDaysInterval, Checklist checklist, List<String> appliesToModelNames) {
        Assert.hasText(templateName, "Template name is required");
        this.templateName = templateName;
        this.templateType = templateType;
        this.flightHoursInterval = flightHoursInterval;
        this.calendarDaysInterval = calendarDaysInterval;
        this.checklist = checklist;
        if (appliesToModelNames != null) {
            this.appliesToModelNames = appliesToModelNames;
        }
    }

    public Long getPk() {
        return pk;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getTemplateType() {
        return templateType;
    }

    public Double getFlightHoursInterval() {
        return flightHoursInterval;
    }

    public Integer getCalendarDaysInterval() {
        return calendarDaysInterval;
    }

    public Checklist getChecklist() {
        return checklist;
    }

    public List<String> getAppliesToModelNames() {
        return appliesToModelNames;
    }

    public String getGlobalId() { return globalId; }
    public long getRevision() { return revision; }
    public Instant getLastModified() { return lastModified; }

    public static MaintenanceTemplate fromReplica(String globalId, String templateName, String templateType,
                                                  Double flightHoursInterval, Integer calendarDaysInterval,
                                                  Checklist checklist, List<String> modelNames,
                                                  long revision, Instant lastModified) {
        MaintenanceTemplate template = new MaintenanceTemplate(templateName, templateType,
                flightHoursInterval, calendarDaysInterval, checklist, modelNames);
        template.globalId = globalId;
        template.revision = revision;
        template.lastModified = lastModified;
        return template;
    }

    public boolean mergeReplica(String templateType, Double flightHoursInterval, Integer calendarDaysInterval,
                                Checklist checklist, List<String> modelNames, long incomingRevision,
                                Instant incomingLastModified) {
        if (incomingRevision <= revision) return false;
        this.templateType = templateType;
        this.flightHoursInterval = flightHoursInterval;
        this.calendarDaysInterval = calendarDaysInterval;
        this.checklist = checklist;
        this.appliesToModelNames = modelNames == null ? new ArrayList<>() : new ArrayList<>(modelNames);
        this.revision = incomingRevision;
        this.lastModified = incomingLastModified;
        return true;
    }
}
