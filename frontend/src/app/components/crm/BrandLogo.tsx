import { colors } from "../../tokens";

/**
 * The TechCRM mark: three bars rising like a chart.
 *
 * `animated` staggers the bars so it reads as a loading indicator rather than
 * decoration. Motion is defined in styles/animations.css, which also switches
 * it off under prefers-reduced-motion.
 */
export function BrandMark({ size = 32, animated = false }: { size?: number; animated?: boolean }) {
  const bars = [
    { x: 5, h: 9, delay: "0s" },
    { x: 13, h: 15, delay: "0.15s" },
    { x: 21, h: 22, delay: "0.3s" },
  ];

  return (
    <svg width={size} height={size} viewBox="0 0 32 32" role="img" aria-label="TechCRM">
      <rect width="32" height="32" rx="8" fill={colors.primary} />
      {bars.map((bar) => (
        <rect
          key={bar.x}
          x={bar.x}
          y={26 - bar.h}
          width="6"
          height={bar.h}
          rx="1.5"
          fill="#FFFFFF"
          className={animated ? "brand-anim-bar" : undefined}
          style={animated ? { animationDelay: bar.delay } : undefined}
        />
      ))}
    </svg>
  );
}

export function BrandWordmark({ size = 18 }: { size?: number }) {
  return (
    <span style={{ fontSize: size, fontWeight: 600, color: colors.textPrimary, letterSpacing: -0.2 }}>
      <span style={{ color: colors.primary }}>Tech</span>CRM
    </span>
  );
}

/** Logo + wordmark lockup, used in the login panel and the top of forms. */
export function BrandLockup({ size = 32, animated = false }: { size?: number; animated?: boolean }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <BrandMark size={size} animated={animated} />
      <BrandWordmark size={size * 0.56} />
    </div>
  );
}

/**
 * Full-screen loading state. The halo pulses behind an animated mark so the
 * wait reads as "working", not "stuck", and a caption appears for waits long
 * enough to notice.
 */
export function BrandLoader({ label = "Loading your workspace…" }: { label?: string }) {
  return (
    <div
      style={{
        height: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 20,
        background: colors.bgPrimary,
        fontFamily: "Inter, system-ui, sans-serif",
      }}
    >
      <div style={{ position: "relative", display: "grid", placeItems: "center" }}>
        <div
          className="brand-anim-pulse"
          style={{
            position: "absolute",
            width: 96,
            height: 96,
            borderRadius: "50%",
            background: colors.primary,
          }}
        />
        <div style={{ position: "relative" }}>
          <BrandMark size={52} animated />
        </div>
      </div>

      <div className="brand-anim-fade-up" style={{ textAlign: "center", animationDelay: "0.2s" }}>
        <BrandWordmark size={16} />
        <div style={{ fontSize: 12, color: colors.textSecondary, marginTop: 6 }}>{label}</div>
      </div>
    </div>
  );
}

/** Small inline spinner for buttons mid-request. */
export function Spinner({ size = 14, color = "#FFFFFF" }: { size?: number; color?: string }) {
  return (
    <span
      className="brand-anim-spin"
      aria-hidden="true"
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        border: `2px solid ${color}`,
        borderTopColor: "transparent",
        display: "inline-block",
        flexShrink: 0,
      }}
    />
  );
}
