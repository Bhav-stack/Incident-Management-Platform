// Inline SVG icons, same set and geometry as the approved mockup. No icon
// fonts, no emoji: every glyph is a path that inherits currentColor.

interface IconProps {
  size?: number;
  strokeWidth?: number;
}

export function ShieldIcon({ size = 16 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path d="M10 2 16.5 5v4.6c0 3.6-2.6 6.4-6.5 8.4-3.9-2-6.5-4.8-6.5-8.4V5L10 2Z" fill="currentColor" />
    </svg>
  );
}

export function PowerIcon({ size = 12 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth={1.8} strokeLinecap="round" aria-hidden="true">
      <path d="M8 1.5v6" />
      <path d="M4.8 3.4a5.4 5.4 0 1 0 6.4 0" />
    </svg>
  );
}

export function BoltIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <path d="M9.5 1.5 3.5 9H8l-1 5.5 6-7.5H8l1.5-5.5Z" />
    </svg>
  );
}

export function XIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth={1.8} strokeLinecap="round" aria-hidden="true">
      <path d="M4 4l8 8M12 4l-8 8" />
    </svg>
  );
}

export function RefreshIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 6.5h6.5a3.5 3.5 0 1 1 0 7H6" />
      <path d="M6 3.5 3 6.5l3 3" />
    </svg>
  );
}

export function WarnIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M8 2.5 14.5 13h-13L8 2.5Z" />
      <path d="M8 6.8v3.4" />
      <path d="M8 12.4h.01" />
    </svg>
  );
}

export function CheckIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 8.5 6.5 12 13 4.5" />
    </svg>
  );
}