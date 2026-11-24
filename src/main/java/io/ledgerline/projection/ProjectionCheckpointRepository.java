package io.ledgerline.projection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectionCheckpointRepository extends JpaRepository<ProjectionCheckpoint, String> {
}
