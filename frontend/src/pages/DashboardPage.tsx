import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Account, PortfolioView, api } from "../api";
import { usePriceSync } from "./usePriceSync";
import { formatDateTime } from "../format";
import AccountFilter from "../components/AccountFilter";
import AllocationBar from "../components/AllocationBar";
import Header from "../components/Header";
import PositionList from "../components/PositionList";
import { colorKeys } from "../palette";
import ValueChart from "../components/ValueChart";
import Warnings from "../components/Warnings";

export default function DashboardPage() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [params, setParams] = useSearchParams();
  const account = params.get("account") || "all";
  const setAccount = (id: string) => setParams({ account: id });
  const [view, setView] = useState<PortfolioView | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Colour order always comes from the unfiltered ("all") view (by first activity day), so a position
  // keeps its colour when the account filter changes or new positions are added, also on a filtered mount.
  const [colorOrder, setColorOrder] = useState<string[]>([]);
  const [reload, setReload] = useState(0);
  const sync = usePriceSync(useCallback(() => setReload((n) => n + 1), []));

  useEffect(() => {
    api.get<Account[]>("/api/accounts").then(setAccounts, () => {});
  }, []);

  useEffect(() => {
    let current = true;
    setError(null);
    const selected = api.get<PortfolioView>(`/api/portfolio?account=${encodeURIComponent(account)}`);
    selected.then(
      (v) => { if (current) setView(v); },
      (e: Error) => { if (current) setError(e.message); },
    );
    const all = account === "all" ? selected : api.get<PortfolioView>("/api/portfolio?account=all");
    all.then(
      (v) => { if (current) setColorOrder(colorKeys(v.positions)); },
      () => { /* no colour order: segments stay neutral */ },
    );
    return () => { current = false; };
  }, [account, reload]);

  return (
    <main className="page">
      {view && <Warnings warnings={view.warnings} />}
      {error && <p role="alert" className="error">{error}</p>}
      <AccountFilter accounts={accounts} selected={account} onSelect={setAccount} />
      {!view ? (
        !error && <p className="center">Ładowanie…</p>
      ) : view.empty ? (
        <section className="empty">
          <p>Dodaj pierwszy eksport XTB lub zakup obligacji</p>
          <Link className="button" to="/update">Aktualizuj dane</Link>
        </section>
      ) : (
        <div className="layout">
          <div className="primary">
            <Header summary={view.summary}
              unpriced={view.positions.filter((p) => p.kind === "INSTRUMENT" && p.priceDate === null).length} />
            <ValueChart series={view.series} />
            <AllocationBar positions={view.positions} allKeys={colorOrder} />
          </div>
          <div className="secondary">
            <PositionList positions={view.positions} account={account} />
            <Link className="button" to="/update">Aktualizuj dane</Link>
            <div className="price-refresh">
              <button type="button" onClick={() => void sync.refresh()} disabled={sync.running}>
                {sync.running ? "Odświeżanie…" : "Odśwież notowania"}
              </button>
              {sync.notBefore && (
                <p className="label">Serwis notowań ogranicza zapytania. Kolejna próba nie wcześniej niż {formatDateTime(sync.notBefore)}.</p>
              )}
              {sync.error && <p role="alert" className="error">{sync.error}</p>}
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
