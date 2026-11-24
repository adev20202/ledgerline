package io.ledgerline.projection;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "projection_checkpoints")
public class ProjectionCheckpoint {

    @Id
    private String name;

    @Column(name = "last_event_id", nullable = false)
    private long lastEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectionCheckpoint() {
    }

    ProjectionCheckpoint(String name, Instant at) {
        this.name = name;
        this.lastEventId = 0;
        this.updatedAt = at;
    }

    void advanceTo(long eventId, Instant at) {
        this.lastEventId = eventId;
        this.updatedAt = at;
    }

    public String getName() {
        return name;
    }

    public long getLastEventId() {
        return lastEventId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
