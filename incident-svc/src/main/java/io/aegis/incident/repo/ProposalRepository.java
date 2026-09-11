package io.aegis.incident.repo;

import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.ProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProposalRepository extends JpaRepository<Proposal, Long> {

    Optional<Proposal> findByExternalId(UUID externalId);

    List<Proposal> findByIncidentExternalIdOrderByCreatedAtDesc(UUID incidentExternalId);

    List<Proposal> findByStatusAndCreatedAtBefore(ProposalStatus status, Instant createdAt);
}