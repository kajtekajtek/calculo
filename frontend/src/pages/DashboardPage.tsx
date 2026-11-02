import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Account, PortfolioView, api } from "../api";
import AccountFilter from "../components/AccountFilter";
import AllocationBar from "../components/AllocationBar";
import Header from "../components/Header";
import PositionList from "../components/PositionList";
import { colorKeys } from "../palette";
import ValueChart from "../components/ValueChart";
import Warnings from "../components/Warnings";

export default function DashboardPage() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [account, setAccount] = useState("all");
  const [view, setView] = useState<PortfolioView | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Colour order comes from the unfiltered ("all") view (by first activity day), so a position
  // keeps its colour when the account filter changes or new positions are added.
  const [colorOrder, setColorOrder] = useState<string[]>([]);

  useEffect(() => {
    api.get<Account[]>("/api/accounts").then(setAccounts, () => {});
  }, []);

  useEffect(() => {
    let current = true;
    setError(null);
    api.get<PortfolioView>(`/api/portfolio?account=${encodeURIComponent(account)}`).then(
      (v) => {
        if (!current) return;
        setView(v);
        if (account === "all") {
          setColorOrder(colorKeys(v.positions));
        }
      },
      (e: Error) => { if (current) setError(e.message); },
    );
    return () => { current = false; };
  }, [account]);

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
            <Header summary={view.summary} />
            <ValueChart series={view.series} />
            <AllocationBar positions={view.positions} allKeys={colorOrder} />
          </div>
          <div className="secondary">
            <PositionList positions={view.positions} />
            <Link className="button" to="/update">Aktualizuj dane</Link>
          </div>
        </div>
      )}
    </main>
  );
}
