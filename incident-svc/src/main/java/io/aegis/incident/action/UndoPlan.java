package io.aegis.incident.action;

import java.util.Map;

/**
 * The inverse of an action, captured <b>before</b> execution.
 *
 * @param type   inverse action type, or "none" when the action has no inverse
 *               (e.g. a restart is verification-gated; its undo is escalation)
 * @param params parameters for the inverse (original replica count, version)
 * @param note   human-readable description for the audit trail
 */
public record UndoPlan(String type, Map<String, Object> params, String note) {

    public static final String NONE = "none";
}