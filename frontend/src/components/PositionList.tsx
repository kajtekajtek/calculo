import { Link } from "react-router-dom";
import { PositionRow } from "../api";
import { formatPct, formatPln, formatShare, priceNote } from "../format";

export default function PositionList({ positions, account }: { positions: PositionRow[]; account: string }) {
  return (
    <ul className="positions" aria-label="Pozycje">
      {positions.map((p) => {
        const note = priceNote(p);
        return (
        <li key={p.key}>
          {p.kind === "CASH" ? (
            <span className="name">{p.name}</span>
          ) : (
            <Link className="name" to={`/positions/${encodeURIComponent(p.key)}?account=${encodeURIComponent(account)}`}>
              {p.name}
            </Link>
          )}
          <span className="value">{formatPln(p.value)}</span>
          {p.sharePct !== null && <span className="share">{formatShare(p.sharePct)}</span>}
          {p.gainPct !== null && (
            <span className={`pct ${p.gainPct > 0 ? "gain" : p.gainPct < 0 ? "loss" : ""}`}>
              {formatPct(p.gainPct)}
            </span>
          )}
          {note && <span className="price-note">{note}</span>}
        </li>
        );
      })}
    </ul>
  );
}
