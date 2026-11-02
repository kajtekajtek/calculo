import { PositionRow } from "../api";
import { formatPct, formatPln, formatShare } from "../format";

export default function PositionList({ positions }: { positions: PositionRow[] }) {
  return (
    <ul className="positions" aria-label="Pozycje">
      {positions.map((p) => (
        <li key={p.key}>
          <span className="name">{p.name}</span>
          <span className="value">{formatPln(p.value)}</span>
          <span className="share">{formatShare(p.sharePct)}</span>
          {p.gainPct !== null && (
            <span className={`pct ${p.gainPct > 0 ? "gain" : p.gainPct < 0 ? "loss" : ""}`}>
              {formatPct(p.gainPct)}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}
