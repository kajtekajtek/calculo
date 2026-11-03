import { Summary } from "../api";
import { formatDay, formatPct, formatPln, formatSignedPln } from "../format";

function tone(value: number | null): string {
  if (value === null || value === 0) return "";
  return value > 0 ? "gain" : "loss";
}

/** `unpriced` counts instruments valued at cost: with any of those, a price date would imply freshness. */
export default function Header({ summary, unpriced = 0 }: { summary: Summary; unpriced?: number }) {
  const { value, gain, gainPct, returnValue, returnAnnualized } = summary;
  return (
    <section className="header" aria-label="Podsumowanie">
      <p className="label">Wartość portfela</p>
      <p className="total">{formatPln(value)}</p>
      <p className={`gain-line ${tone(gain)}`}>
        <span>{formatSignedPln(gain)}</span> <span>{formatPct(gainPct)}</span>
      </p>
      <p className="return" aria-label="Zwrot">
        <span className={`return-value ${tone(returnValue)}`}>{formatPct(returnValue)}</span>{" "}
        <span className="label">{returnAnnualized ? "stopa ważona rocznie" : "wynik od początku"}</span>
      </p>
      {unpriced > 0 ? (
        <p className="label">Bez ceny: {unpriced} poz. (wycena po koszcie)</p>
      ) : (
        summary.pricesAsOf && <p className="label">Ceny z dnia {formatDay(summary.pricesAsOf)}</p>
      )}
    </section>
  );
}
