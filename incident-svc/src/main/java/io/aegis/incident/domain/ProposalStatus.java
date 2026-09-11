package io.aegis.incident.domain;

/** Lifecycle of a recovery proposal. */
public enum ProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    EXECUTED
}