package io.aegis.incident.repo;

import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByExternalId(UUID externalId);

    List<Incident> findAllByOrderByOpenedAtDesc();

    List<Incident> findByStatus(IncidentStatus status);

    Optional<Incident> findFirstByServiceAndStatusIn(String service,
                                                     Collection<IncidentStatus> statuses);
}