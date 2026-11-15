package pt.isep.psoft.aisafe.airports_routes_service.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class ReplicationTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String peerUrl;
    @Column(nullable = false) private String httpMethod;
    @Column(nullable = false) private String path;
    @Column(columnDefinition = "TEXT") private String payload;
    @Column(nullable = false) private String originalUser;
    private int attempts;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    private Instant nextAttemptAt;
    @Column(nullable = false) private Instant createdAt;
    private Instant lastModified;
    private String originNodeId;
    public enum Status { PENDING, DONE, FAILED }
    protected ReplicationTask() {}
    public ReplicationTask(String peerUrl, String httpMethod, String path, String payload) {
        this(peerUrl, httpMethod, path, payload, "ANONYMOUS");
    }
    public ReplicationTask(String peerUrl, String httpMethod, String path, String payload, String originalUser) {
        this.peerUrl = peerUrl; this.httpMethod = httpMethod; this.path = path; this.payload = payload;
        this.originalUser = originalUser == null || originalUser.isBlank() ? "ANONYMOUS" : originalUser;
        this.createdAt = Instant.now(); this.nextAttemptAt = this.createdAt;
    }
    public ReplicationTask(String peerUrl, String httpMethod, String path, String payload, String originalUser,
                           Instant lastModified, String originNodeId) {
        this(peerUrl, httpMethod, path, payload, originalUser);
        this.lastModified = lastModified; this.originNodeId = originNodeId;
    }
    public Long getId(){ return id; } public String getPeerUrl(){return peerUrl;} public String getHttpMethod(){return httpMethod;}
    public String getPath(){return path;} public String getPayload(){return payload;} public int getAttempts(){return attempts;}
    public String getOriginalUser(){return originalUser;}
    public Instant getLastModified(){return lastModified;} public String getOriginNodeId(){return originNodeId;}
    public Status getStatus(){return status;} public Instant getNextAttemptAt(){return nextAttemptAt;}
    public Instant getCreatedAt(){return createdAt;}
    public void markDone(){status=Status.DONE;} public void fail(Instant next){attempts++; nextAttemptAt=next; if(attempts>=10) status=Status.FAILED;}
}
