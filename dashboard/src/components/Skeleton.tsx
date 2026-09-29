/* Loading placeholders.
 *
 * Three different states used to look the same on the dashboard: "still
 * loading", "loaded, nothing there yet" and "backend unreachable". That is how
 * an empty incident feed gets misread as a healthy system, so loading now has
 * its own shape: grey rows that match the final layout, and never a spinner in
 * the middle of an empty card.
 *
 * The blocks are `aria-hidden` and the container carries `aria-busy`, so a
 * screen reader announces "busy" once instead of reading placeholder shapes.
 */

function Line({ width }: { width: string }) {
  return <span className="sk-line" style={{ width }} />;
}

/** The four or five stat cards above the feed. */
export function StatsSkeleton({ count = 5 }: { count?: number }) {
  return (
    <div className="stats" aria-busy="true" aria-live="polite">
      {Array.from({ length: count }).map((_, i) => (
        <div className="stat" key={i}>
          <div aria-hidden="true">
            <Line width="58%" />
            <span className="sk-block sk-stat" />
            <Line width="44%" />
          </div>
        </div>
      ))}
      <span className="sr-only">Loading incident statistics</span>
    </div>
  );
}

/** Row placeholders sized like the real incident rows. */
export function FeedSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div className="sk-feed" aria-busy="true" aria-live="polite">
      {Array.from({ length: rows }).map((_, i) => (
        <div className="inc-row sk-row" key={i} aria-hidden="true">
          <Line width="82%" />
          <Line width="70%" />
          <span className="sk-block sk-chip" />
          <Line width="76%" />
          <Line width="94%" />
          <Line width="54%" />
          <Line width="48%" />
        </div>
      ))}
      <span className="sr-only">Loading incidents</span>
    </div>
  );
}

/** Detail view: header, trace rows, timeline and the proposal card. */
export function DetailSkeleton() {
  return (
    <section className="view active" aria-busy="true" aria-live="polite">
      <div className="card inc-header">
        <span className="sk-block sk-chip" aria-hidden="true" />
        <div className="title" aria-hidden="true">
          <span className="sk-block sk-title" />
          <span className="sk-block sk-meta" />
        </div>
      </div>
      <div className="detail-grid">
        <div>
          {[3, 4].map((rows, card) => (
            <div className="card" key={card} style={card > 0 ? { marginTop: 20 } : undefined}>
              <div className="card-head" aria-hidden="true"><span className="sk-block sk-meta" /></div>
              {Array.from({ length: rows }).map((_, i) => (
                <div className="sk-row sk-detail-row" key={i} aria-hidden="true">
                  <div><Line width="60%" /><Line width="88%" /></div>
                  <Line width="72%" />
                  <Line width="40%" />
                </div>
              ))}
            </div>
          ))}
        </div>
        <div className="proposal">
          <div className="card" aria-hidden="true">
            <div className="card-head"><span className="sk-block sk-meta" /></div>
            <div className="body">
              <span className="sk-block sk-title" />
              <span className="sk-block sk-bar" />
              <span className="sk-block sk-meta" />
              <span className="sk-block sk-meta" />
              <span className="sk-block sk-btn" />
            </div>
          </div>
        </div>
      </div>
      <span className="sr-only">Loading incident detail</span>
    </section>
  );
}

/** Control surface rows, so a slow control plane reads as loading, not broken. */
export function RowsSkeleton({ rows = 3, label }: { rows?: number; label: string }) {
  return (
    <div aria-busy="true" aria-live="polite">
      {Array.from({ length: rows }).map((_, i) => (
        <div className="switch-row" key={i} aria-hidden="true">
          <div style={{ flex: 1 }}>
            <Line width="38%" />
            <span className="sk-block sk-meta" />
          </div>
          <span className="sk-block sk-switch" />
        </div>
      ))}
      <span className="sr-only">{label}</span>
    </div>
  );
}
