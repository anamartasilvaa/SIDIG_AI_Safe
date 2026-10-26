package pt.isep.psoft.aisafe.maintenance.domain;

import jakarta.persistence.*;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

@Entity
public class MaintenanceTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

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
}
