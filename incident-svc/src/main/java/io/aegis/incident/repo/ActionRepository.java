package io.aegis.incident.repo;

import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.ActionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActionRepository extends JpaRepository<Action, Long> {

    boolean existsByCommandId(UUID commandId);

    List<Action> findByStatus(ActionStatus status);
}