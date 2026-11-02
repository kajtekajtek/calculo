import { PositionRow } from "../api";
import { formatShare } from "../format";
import { OTHER_COLOR, colorFor, colorKeys } from "../palette";

const MAX_SEGMENTS = 8;
const OTHER_KEY = "__other";

interface Segment { key: string; name: string; value: number; sharePct: number; color: string }

function toSegments(positions: PositionRow[], allKeys: readonly string[]): Segment[] {
  const sorted = [...positions].filter((p) => p.value > 0).sort((a, b) => b.value - a.value);
  const kept: Segment[] = [];
  const rest: PositionRow[] = [];
  for (const p of sorted) {
    const color = colorFor(p.key, allKeys);
    // Beyond eight positions the smallest are folded into "Inne", whatever their colour.
    if (kept.length < MAX_SEGMENTS) {
      kept.push({ key: p.key, name: p.name, value: p.value, sharePct: p.sharePct, color });
    } else {
      rest.push(p);
    }
  }
  if (rest.length > 0) {
    kept.push({
      key: OTHER_KEY,
      name: "Inne",
      value: rest.reduce((sum, p) => sum + p.value, 0),
      sharePct: rest.reduce((sum, p) => sum + p.sharePct, 0),
      color: OTHER_COLOR,
    });
  }
  return kept;
}

/**
 * Thin segmented bar: one whole split into positions. No numbers on segments; the legend
 * carries names and shares. `allKeys` should be `colorKeys` of the unfiltered view so
 * colours stay stable when the account filter changes.
 */
export default function AllocationBar({ positions, allKeys }: { positions: PositionRow[]; allKeys?: readonly string[] }) {
  const keys = allKeys ?? colorKeys(positions);
  const segments = toSegments(positions, keys);
  if (segments.length === 0) return null;
  const summary = segments.map((s) => `${s.name} ${formatShare(s.sharePct)}`).join(", ");
  return (
    <div className="allocation">
      <div className="allocation-bar" role="img" aria-label={`Podział portfela: ${summary}`}>
        {segments.map((s) => (
          <span
            key={s.key}
            data-key={s.key}
            title={`${s.name} ${formatShare(s.sharePct)}`}
            style={{ flexGrow: s.value, backgroundColor: s.color }}
          />
        ))}
      </div>
      <div className="allocation-legend" data-testid="allocation-legend">
        {segments.map((s) => (
          <span key={s.key} className="legend-item">
            <i className="swatch" style={{ backgroundColor: s.color }} aria-hidden="true" />
            <span className="legend-name">{s.name}</span>
            <span className="legend-share">{formatShare(s.sharePct)}</span>
          </span>
        ))}
      </div>
    </div>
  );
}
