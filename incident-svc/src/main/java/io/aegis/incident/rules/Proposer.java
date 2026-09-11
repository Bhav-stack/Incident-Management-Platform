package io.aegis.incident.rules;

import io.aegis.contracts.events.AnomalyEvent;

import java.util.Optional;

/**
 * Turns a confirmed anomaly into a recovery proposal draft.
 *
 * <p>This is the interface the tool-calling agent replaces in Phase 4: the
 * rest of the pipeline (persistence, approval gate, execution) is agnostic to
 * whether the proposal came from a rule or from an LLM.
 */
public interface Proposer {

    Optional<ProposalDraft> propose(AnomalyEvent anomaly);
}