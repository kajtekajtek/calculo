import { Summary } from "../api";
import { formatPct, formatPln, formatSignedPln } from "../format";

function tone(value: number | null): string {
  if (value === null || value === 0) return "";
  return value > 0 ? "gain" : "loss";
}

export default function Header({ summary }: { summary: Summary }) {
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
      {summary.pricesAsOf && <p className="label">Ceny z dnia {summary.pricesAsOf}</p>}
    </section>
  );
}
