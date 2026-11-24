package io.ledgerline.common;

import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

/**
 * Base for entities whose UUID is set by the application. Without this,
 * Spring Data treats any entity with a non-null id as existing and calls
 * merge, which costs an extra select per insert.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newEntity = false;
    }
}
