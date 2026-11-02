import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, type BondPurchase } from "../api";
import { parseDecimal, todayLocal } from "../inputs";

/** Manual EDO bond entry: add, edit and delete purchases. */
export default function EdoForm() {
  const [bonds, setBonds] = useState<BondPurchase[]>([]);
  const [date, setDate] = useState(() => todayLocal());
  const [quantity, setQuantity] = useState("");
  const [needsRates, setNeedsRates] = useState<string | null>(null);
  const [firstYear, setFirstYear] = useState("");
  const [margin, setMargin] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  const reload = useCallback(async () => {
    try {
      setBonds(await api.get<BondPurchase[]>("/api/bonds"));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Nie udało się wczytać obligacji.");
    }
  }, []);
  useEffect(() => { void reload(); }, [reload]);

  async function guarded(action: () => Promise<void>) {
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

  function resetForm() {
    setDate(todayLocal());
    setQuantity("");
    setNeedsRates(null);
    setFirstYear("");
    setMargin("");
    setEditingId(null);
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const qty = parseDecimal(quantity);
    if (qty === null || !Number.isInteger(qty) || qty < 1) {
      setError("Liczba sztuk musi być liczbą całkowitą od 1.");
      return;
    }
    if (!date) {
      setError("Podaj datę zakupu.");
      return;
    }
    const body: Record<string, unknown> = { purchaseDate: date, quantity: qty };
    if (needsRates) {
      const rate = parseDecimal(firstYear);
      const m = parseDecimal(margin);
      if (rate === null) { setError("Oprocentowanie w pierwszym roku musi być liczbą, np. 6,5."); return; }
      if (m === null) { setError("Marża musi być liczbą, np. 2."); return; }
      body.firstYearRate = rate;
      body.margin = m;
    }
    void guarded(async () => {
      try {
        const result = editingId === null
          ? await api.post<BondPurchase>("/api/bonds", body)
          : await api.put<BondPurchase>(`/api/bonds/${editingId}`, body);
        setSaved(`Zapisano: seria ${result.seriesCode}, ${result.quantity} szt.`);
        resetForm();
        await reload();
      } catch (e) {
        const data = e instanceof ApiError ? (e.body as { needsRates?: boolean; seriesCode?: string } | undefined) : undefined;
        if (e instanceof ApiError && e.status === 409 && data?.needsRates) {
          setNeedsRates(data.seriesCode ?? "");
          setError(e.message);
          return;
        }
        throw e;
      }
    });
  }

  function edit(b: BondPurchase) {
    resetForm();
    setSaved(null);
    setEditingId(b.id);
    setDate(b.purchaseDate);
    setQuantity(String(b.quantity));
  }

  function remove(b: BondPurchase) {
    if (!window.confirm(`Usunąć zakup ${b.seriesCode} (${b.quantity} szt.) z ${b.purchaseDate}?`)) return;
    void guarded(async () => {
      await api.del(`/api/bonds/${b.id}`);
      if (editingId === b.id) resetForm();
      setSaved(null);
      await reload();
    });
  }

  return (
    <section className="card">
      <h2>Obligacje EDO</h2>
      <form className="form" onSubmit={submit} noValidate>
        <label>
          Data zakupu
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        <label>
          Liczba sztuk
          <input inputMode="numeric" pattern="[0-9]*" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </label>
        {needsRates && (
          <>
            <p className="label">Nie znaleziono oprocentowania serii {needsRates}. Podaj je ręcznie.</p>
            <label>
              Oprocentowanie w pierwszym roku (%)
              <input inputMode="decimal" value={firstYear} onChange={(e) => setFirstYear(e.target.value)} />
            </label>
            <label>
              Marża (%)
              <input inputMode="decimal" value={margin} onChange={(e) => setMargin(e.target.value)} />
            </label>
          </>
        )}
        {error && <p role="alert" className="error">{error}</p>}
        {saved && <p className="ok">{saved}</p>}
        <button type="submit" disabled={busy}>
          {busy ? "Zapisywanie…" : editingId === null ? "Zapisz obligacje" : "Zapisz zmiany"}
        </button>
        {editingId !== null && <button type="button" onClick={resetForm} disabled={busy}>Anuluj edycję</button>}
      </form>
      {bonds.length > 0 && (
        <ul className="rows">
          {bonds.map((b) => (
            <li key={b.id}>
              <span className="grow">{b.seriesCode} · {b.purchaseDate} · {b.quantity} szt.</span>
              <button type="button" onClick={() => edit(b)} disabled={busy} aria-label={`Edytuj ${b.seriesCode} z ${b.purchaseDate}`}>Edytuj</button>
              <button type="button" onClick={() => remove(b)} disabled={busy} aria-label={`Usuń ${b.seriesCode} z ${b.purchaseDate}`}>Usuń</button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
