import { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ApiError, PositionDetail, api } from "../api";
import ValueChart from "../components/ValueChart";
import { formatDay, formatDecimal, formatPln, priceNote } from "../format";

export default function PositionPage() {
  const { key = "" } = useParams();
  const [params] = useSearchParams();
  const account = params.get("account") || "all";
  const [detail, setDetail] = useState<PositionDetail | null>(null);
  const [error, setError] = useState<{ notFound: boolean; message: string } | null>(null);
  const back = <p><Link to={`/?account=${encodeURIComponent(account)}`}>Wróć do portfela</Link></p>;

  useEffect(() => {
    let current = true;
    setDetail(null);
    setError(null);
    api.get<PositionDetail>(`/api/portfolio/positions/${encodeURIComponent(key)}?account=${encodeURIComponent(account)}`).then(
      (d) => { if (current) setDetail(d); },
      (e: Error) => { if (current) setError({ notFound: e instanceof ApiError && e.status === 404, message: e.message }); },
    );
    return () => { current = false; };
  }, [key, account]);

  if (error) {
    return (
      <main className="page">
        {error.notFound ? <p>Nie znaleziono pozycji</p> : <p role="alert" className="error">{error.message}</p>}
        {back}
      </main>
    );
  }
  if (!detail) return <main className="page"><p className="center">Ładowanie…</p></main>;

  const note = priceNote(detail.row);
  return (
    <main className="page">
      {back}
      <h1>{detail.row.name}</h1>
      <p className="total">{formatPln(detail.row.value)}</p>
      {note && <p className="label">{note}</p>}
      <ValueChart series={detail.series} hideReturnToggle costLabel="Koszt" />
      <ul className="positions" aria-label="Zakupy">
        {detail.purchases.map((p, i) => (
          <li key={i}>
            <span className="name">{p.label}</span>
            <span className="value">{formatPln(p.valueNow)}</span>
            <span className="share">
              {formatDay(p.day)} · {p.accountName} · {formatDecimal(p.volume)} szt.
              {p.price !== null && ` · cena ${formatDecimal(p.price)}`} · kwota {formatPln(p.amountPln)}
            </span>
          </li>
        ))}
      </ul>
    </main>
  );
}
