import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, ApiError, type RatesResponse } from "../api";
import { parseDecimal } from "../inputs";

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** Runs one request at a time; a second tap while pending is ignored. */
function useGuard() {
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function run(action: () => Promise<void>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Nie udało się zapisać.");
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }
  return { busy, error, setError, run };
}

export default function RatesPage() {
  const [rates, setRates] = useState<RatesResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const guard = useGuard();
  const [newMonth, setNewMonth] = useState("");
  const [newPct, setNewPct] = useState("");

  const reload = useCallback(async () => {
    try {
      setRates(await api.get<RatesResponse>("/api/rates"));
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : "Nie udało się wczytać stóp.");
    }
  }, []);
  useEffect(() => { void reload(); }, [reload]);

  if (loadError) return <main className="page"><p role="alert" className="error">{loadError}</p></main>;
  if (!rates) return <p className="center">Ładowanie…</p>;

  const missing = new Set(rates.missingCpi);
  const months = [...new Set([...rates.cpi.map((c) => c.month), ...rates.missingCpi])].sort();
  const known = new Map(rates.cpi.map((c) => [c.month, c.pct]));

  function addCpi(e: React.FormEvent) {
    e.preventDefault();
    const pct = parseDecimal(newPct);
    if (!MONTH.test(newMonth)) { guard.setError("Podaj miesiąc w formacie rrrr-MM, np. 2026-03."); return; }
    if (pct === null) { guard.setError("Inflacja musi być liczbą, np. 3,5."); return; }
    void guard.run(async () => {
      await api.put(`/api/rates/cpi/${newMonth}`, { pct });
      setNewMonth("");
      setNewPct("");
      await reload();
    });
  }

  return (
    <main className="page">
      <h1>Stopy i inflacja</h1>
      <p><Link to="/update">Wróć do aktualizacji</Link></p>
      {guard.error && <p role="alert" className="error">{guard.error}</p>}

      <section className="card">
        <h2>Serie EDO</h2>
        {rates.series.length === 0 && <p className="label">Brak zapisanych serii.</p>}
        <ul className="rows">
          {rates.series.map((s) => (
            <SeriesRow key={s.code} code={s.code} firstYearRate={s.firstYearRate} margin={s.margin}
                       busy={guard.busy} run={guard.run} setError={guard.setError} reload={reload} />
          ))}
        </ul>
      </section>

      <section className="card">
        <h2>Inflacja (CPI)</h2>
        {rates.missingCpi.length > 0 && (
          <p className="missing">Brakuje odczytów inflacji: {rates.missingCpi.join(", ")}. Uzupełnij je, żeby wartość obligacji była dokładna.</p>
        )}
        <ul className="rows">
          {months.map((m) => (
            <CpiRow key={`${m}:${known.get(m) ?? ""}`} month={m} pct={known.get(m)} isMissing={missing.has(m)}
                    busy={guard.busy} run={guard.run} setError={guard.setError} reload={reload} />
          ))}
        </ul>
        <form className="inline-form" onSubmit={addCpi} noValidate>
          <label>
            Miesiąc (rrrr-MM)
            <input value={newMonth} onChange={(e) => setNewMonth(e.target.value)} placeholder="2026-03" />
          </label>
          <label>
            Nowa inflacja (%)
            <input inputMode="decimal" value={newPct} onChange={(e) => setNewPct(e.target.value)} />
          </label>
          <button type="submit" disabled={guard.busy}>{guard.busy ? "Zapisywanie…" : "Dodaj odczyt"}</button>
        </form>
      </section>
    </main>
  );
}

interface RowProps {
  busy: boolean;
  run: (action: () => Promise<void>) => Promise<void>;
  setError: (message: string | null) => void;
  reload: () => Promise<void>;
}

function fmt(n: number | undefined): string {
  return n === undefined ? "" : String(n).replace(".", ",");
}

function SeriesRow({ code, firstYearRate, margin, busy, run, setError, reload }:
    RowProps & { code: string; firstYearRate: number; margin: number }) {
  const [first, setFirst] = useState(fmt(firstYearRate));
  const [mar, setMar] = useState(fmt(margin));
  function save() {
    const f = parseDecimal(first);
    const m = parseDecimal(mar);
    if (f === null || m === null) { setError(`Oprocentowanie serii ${code} musi być liczbą, np. 6,5.`); return; }
    void run(async () => {
      await api.put(`/api/rates/series/${code}`, { firstYearRate: f, margin: m });
      await reload();
    });
  }
  return (
    <li aria-label={code}>
      <span className="grow">{code}</span>
      <input inputMode="decimal" aria-label={`Oprocentowanie ${code} w pierwszym roku (%)`} value={first}
             onChange={(e) => setFirst(e.target.value)} />
      <input inputMode="decimal" aria-label={`Marża ${code} (%)`} value={mar} onChange={(e) => setMar(e.target.value)} />
      <button type="button" onClick={save} disabled={busy}>Zapisz</button>
    </li>
  );
}

function CpiRow({ month, pct, isMissing, busy, run, setError, reload }:
    RowProps & { month: string; pct: number | undefined; isMissing: boolean }) {
  const [value, setValue] = useState(fmt(pct));
  function save() {
    const parsed = parseDecimal(value);
    if (parsed === null) { setError(`Inflacja ${month} musi być liczbą, np. 3,5.`); return; }
    void run(async () => {
      await api.put(`/api/rates/cpi/${month}`, { pct: parsed });
      await reload();
    });
  }
  function remove() {
    if (!window.confirm(`Usunąć odczyt inflacji z ${month}?`)) return;
    void run(async () => {
      await api.del(`/api/rates/cpi/${month}`);
      await reload();
    });
  }
  return (
    <li aria-label={month}>
      <span className="grow">{month}{isMissing && <strong> — Brak odczytu</strong>}</span>
      <input inputMode="decimal" aria-label={`Inflacja ${month} (%)`} value={value} onChange={(e) => setValue(e.target.value)} />
      <button type="button" onClick={save} disabled={busy}>Zapisz</button>
      {pct !== undefined && <button type="button" onClick={remove} disabled={busy}>Usuń</button>}
    </li>
  );
}
