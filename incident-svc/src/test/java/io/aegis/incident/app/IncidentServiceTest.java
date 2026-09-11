package io.aegis.incident.app;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.repo.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentServiceTest {

    private IncidentRepository incidents;
    private IncidentEventRepository events;
    private IncidentStatePublisher publisher;
    private ProposalService proposals;
    private IncidentService service;

    private static final AnomalyEvent ANOMALY = new AnomalyEvent(
            UUID.randomUUID(), UUID.randomUUID(), "checkout-service",
            "error_rate", 8.5, 5.0, "SEV2", Instant.parse("2026-09-08T10:00:00Z"));

    @BeforeEach
    void setUp() {
        incidents = mock(IncidentRepository.class);
        events = mock(IncidentEventRepository.class);
        publisher = mock(IncidentStatePublisher.class);
        proposals = mock(ProposalService.class);
        service = new IncidentService(incidents, events, publisher, proposals,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void opensIncidentWhenNoActiveIncidentExists() {
        when(incidents.findFirstByServiceAndStatusIn(any(), any())).thenReturn(Optional.empty());

        Incident opened = service.openFromAnomaly(ANOMALY);

        assertEquals(IncidentStatus.INVESTIGATING, opened.getStatus());
        assertEquals(Severity.SEV2, opened.getSeverity());
        assertEquals("checkout-service", opened.getService());
        verify(incidents).saveAndFlush(opened);
        verify(proposals).proposeForAnomaly(opened, ANOMALY);
        verify(publisher).publish(opened);
    }

    @Test
    void mergesEvidenceIntoActiveIncidentInsteadOfOpening() {
        Incident active = new Incident("checkout-service", Severity.SEV3, "existing");
        active.transitionTo(IncidentStatus.INVESTIGATING);
        when(incidents.findFirstByServiceAndStatusIn(any(), any()))
                .thenReturn(Optional.of(active));

        Incident result = service.openFromAnomaly(ANOMALY);

        assertSame(active, result, "must not open a second incident for the same service");
        verify(incidents, never()).saveAndFlush(any(Incident.class));
        verify(events).saveAndFlush(any(IncidentEvent.class));
        verify(proposals, never()).proposeForAnomaly(any(), any());
        verify(publisher).publish(active);
    }

    @Test
    void unknownSeverityFallsBackToSev3() {
        AnomalyEvent unknown = new AnomalyEvent(ANOMALY.eventId(), ANOMALY.sourceEventId(),
                ANOMALY.service(), ANOMALY.signal(), ANOMALY.value(), ANOMALY.threshold(),
                "SEV9", ANOMALY.windowStart());
        when(incidents.findFirstByServiceAndStatusIn(any(), any())).thenReturn(Optional.empty());

        Incident opened = service.openFromAnomaly(unknown);

        assertEquals(Severity.SEV3, opened.getSeverity());
    }

    @Test
    void duplicateEvidenceDeliveryIsSkippedViaDedupKey() {
        Incident active = new Incident("checkout-service", Severity.SEV2, "existing");
        active.transitionTo(IncidentStatus.INVESTIGATING);
        when(incidents.findFirstByServiceAndStatusIn(any(), any()))
                .thenReturn(Optional.of(active));
        // The unique index already rejected this evidence on a prior delivery:
        when(events.saveAndFlush(any(IncidentEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        service.openFromAnomaly(ANOMALY);

        // No crash, no republish storm: the evidence is simply skipped.
        verify(publisher).publish(active);
    }
}