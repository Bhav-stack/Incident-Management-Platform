import type { IncidentStatus, Severity } from "./types";

// Status/severity to style-class mapping. Mirrors design/tokens.css semantics.

export function statusClass(status: IncidentStatus): string {
  switch (status) {
    case "INVESTIGATING":
    case "OPEN":
    case "PROPOSAL":
      return "investigating";
    case "AWAITING_APPROVAL":
      return "awaiting";
    case "EXECUTING":
      return "executing";
    case "VERIFYING":
    case "ROLLING_BACK":
      return "verifying";
    case "RESOLVED":
      return "resolved";
    default:
      return "rolledback";
  }
}

export function sevClass(sev: Severity): string {
  return sev === "SEV1" ? "sev1" : sev === "SEV2" ? "sev2" : "sev3";
}

export function confClass(conf: number): string {
  if (conf >= 0.85) return "hi";
  if (conf >= 0.7) return "mid";
  return "lo";
}

export function riskClass(risk: string): string {
  if (risk === "HIGH") return "risk-high";
  if (risk === "MEDIUM") return "risk-med";
  return "";
}

export function timelineDotColor(eventType: string): string {
  if (eventType.startsWith("ANOMALY") || eventType.startsWith("ACTION_FAILED") || eventType === "OPENED") {
    return "red";
  }
  if (eventType.startsWith("PROPOSAL") || eventType.startsWith("ESCALATED") || eventType.startsWith("EXPIRED")) {
    return "orange";
  }
  if (eventType.startsWith("VERIFIED") || eventType === "RESOLVED") {
    return "green";
  }
  return "blue";
}

export function actionIconClass(verdict: string): string {
  if (verdict === "VERIFY FAIL" || verdict === "FAILED") return "fail";
  if (verdict === "ROLLED BACK" || verdict === "ESCALATED") return "warn";
  return "ok";
}