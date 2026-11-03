import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PortfolioView } from "../api";
import { json, stubApi } from "../test-support";
import DashboardPage from "./DashboardPage";
import PositionPage from "./PositionPage";

const row = { name: "Spółka AAA", kind: "INSTRUMENT", value: 1200, sharePct: 50, gainPct: 20, priceDate: "2026-10-30", firstDay: "2026-03-03" };
const series = [
  { day: "2026-03-03", value: 1000, contributions: 1000, cumulativeReturn: null },
  { day: "2026-10-30", value: 1200, contributions: 1000, cumulativeReturn: null },
];

const instrument = {
  row: { ...row, key: "AAA.DE" },
  purchases: [
    { day: "2026-03-03", accountName: "Konto AAA", label: "AAA.DE", volume: 10, price: 100.5, amountPln: 1000, valueNow: 1200 },
  ],
  series,
};

const edo = {
  row: { ...row, key: "edo", name: "Obligacje EDO", kind: "BONDS" },
  purchases: [
    { day: "2026-03-03", accountName: "Obligacje", label: "EDO0336", volume: 7, price: 100, amountPln: 700, valueNow: 733 },
  ],
  series,
};

function portfolio(): PortfolioView {
  return {
    summary: { value: 2000, contributions: 1800, gain: 200, gainPct: 11, returnValue: 5, returnAnnualized: false, pricesAsOf: null },
    positions: [
      { ...row, key: "AAA.DE", kind: "INSTRUMENT" },
      { ...row, key: "cash", name: "Gotówka", kind: "CASH", gainPct: null },
    ],
    series: [],
    warnings: [],
    empty: false,
  };
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/positions/:key" element={<PositionPage />} />
        <Route path="*" element={<DashboardPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

describe("PositionPage", () => {
  it("shows the chart and the purchase list for an instrument", async () => {
    const calls = stubApi({ "GET /api/portfolio/positions/AAA.DE?account=all": () => json(instrument) });
    renderApp("/positions/AAA.DE");
    expect(await screen.findByRole("heading", { name: "Spółka AAA" })).toBeInTheDocument();
    expect(calls[0].path).toBe("/api/portfolio/positions/AAA.DE?account=all");
    const item = screen.getByRole("listitem");
    expect(item).toHaveTextContent("Konto AAA");
    expect(item).toHaveTextContent("10");
    expect(item).toHaveTextContent("100,5");
    expect(item).toHaveTextContent(/1\s000\szł/);
    expect(item).toHaveTextContent(/1\s200\szł/);
    expect(screen.getByRole("region", { name: "Wykres portfela" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Stopa ważona" })).not.toBeInTheDocument();
    expect(screen.getByTestId("chart-legend")).toHaveTextContent("Koszt");
  });

  it("shows the price date of a priced instrument in Polish", async () => {
    stubApi({ "GET /api/portfolio/positions/AAA.DE?account=all": () => json(instrument) });
    renderApp("/positions/AAA.DE");
    expect(await screen.findByText("cena z 30 paź 2026")).toBeInTheDocument();
  });

  it("says an unpriced instrument is valued at cost", async () => {
    const detail = { ...instrument, row: { ...instrument.row, priceDate: null } };
    stubApi({ "GET /api/portfolio/positions/AAA.DE?account=all": () => json(detail) });
    renderApp("/positions/AAA.DE");
    expect(await screen.findByText("wycena po koszcie")).toBeInTheDocument();
  });

  it("adds no price note to bonds", async () => {
    const detail = { ...edo, row: { ...edo.row, priceDate: null } };
    stubApi({ "GET /api/portfolio/positions/edo?account=all": () => json(detail) });
    renderApp("/positions/edo");
    await screen.findByRole("heading", { name: "Obligacje EDO" });
    expect(screen.queryByText("wycena po koszcie")).not.toBeInTheDocument();
    expect(screen.queryByText(/^cena z/)).not.toBeInTheDocument();
  });

  it("lists EDO purchases by series with quantity and current value", async () => {
    stubApi({ "GET /api/portfolio/positions/edo?account=all": () => json(edo) });
    renderApp("/positions/edo");
    const item = await screen.findByRole("listitem");
    expect(item).toHaveTextContent("EDO0336");
    expect(item).toHaveTextContent("7 szt.");
    expect(item).toHaveTextContent(/733\szł/);
  });

  it("shows cash as a non-link row on the dashboard and links other positions with the account", async () => {
    stubApi({
      "GET /api/accounts": () => json([{ id: "a1", name: "Konto AAA", broker: "XTB" }]),
      "GET /api/portfolio?account=a1": () => json(portfolio()),
      "GET /api/portfolio?account=all": () => json(portfolio()),
    });
    renderApp("/?account=a1");
    const link = await screen.findByRole("link", { name: /Spółka AAA/ });
    expect(link).toHaveAttribute("href", "/positions/AAA.DE?account=a1");
    const cash = within(screen.getByRole("list", { name: "Pozycje" })).getByText("Gotówka");
    expect(cash.closest("a")).toBeNull();
  });

  it("keeps the account filter through the position and back", async () => {
    stubApi({
      "GET /api/accounts": () => json([{ id: "a1", name: "Konto AAA", broker: "XTB" }]),
      "GET /api/portfolio?account=a1": () => json(portfolio()),
      "GET /api/portfolio/positions/AAA.DE?account=a1": () => json(instrument),
    });
    renderApp("/?account=a1");
    await userEvent.click(await screen.findByRole("link", { name: /Spółka AAA/ }));
    await screen.findByRole("heading", { name: "Spółka AAA" });
    const back = screen.getByRole("link", { name: /Wróć/ });
    expect(back).toHaveAttribute("href", "/?account=a1");
    await userEvent.click(back);
    await waitFor(() => expect(screen.getByRole("button", { name: "Konto AAA" })).toHaveAttribute("aria-pressed", "true"));
  });

  it("encodes the key in the request", async () => {
    const calls = stubApi({});
    renderApp(`/positions/${encodeURIComponent("A/B C")}`);
    await screen.findByText("Nie znaleziono pozycji");
    expect(calls[0].path).toBe("/api/portfolio/positions/A%2FB%20C?account=all");
  });

  it("shows not-found with a back link on 404", async () => {
    stubApi({ "GET /api/portfolio/positions/ZZZ.DE?account=all": () => json({ message: "Brak pozycji" }, 404) });
    renderApp("/positions/ZZZ.DE");
    expect(await screen.findByText("Nie znaleziono pozycji")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Wróć/ })).toHaveAttribute("href", "/?account=all");
  });
});
