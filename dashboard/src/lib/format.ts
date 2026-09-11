// Small display helpers. No date library: the formats the design needs are
// a clock and relative ages, both trivial with Intl.

export function nowUtc(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())} UTC`;
}

export function ageOf(iso: string): string {
  const ageMs = Date.now() - new Date(iso).getTime();
  const s = Math.max(0, Math.floor(ageMs / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, "0")}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${String(m % 60).padStart(2, "0")}m`;
}

/** mm:ss countdown from a deadline, clamped at zero. */
export function countdown(deadlineMs: number, nowMs: number = Date.now()): string {
  const s = Math.max(0, Math.floor((deadlineMs - nowMs) / 1000));
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function timeOf(iso: string): string {
  return new Date(iso).toISOString().slice(11, 19);
}

export function pct(value: number): string {
  return `${Math.round(value * 100)}%`;
}