package io.aegis.incident.repo;

import io.aegis.incident.domain.IncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, Long> {

    List<IncidentEvent> findByIncidentIdOrderByCreatedAtAsc(Long incidentId);
}