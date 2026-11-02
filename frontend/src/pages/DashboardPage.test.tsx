import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import type { PortfolioView } from "../api";
import DashboardPage from "./DashboardPage";

const accounts = [
  { id: "a1", name: "Konto AAA", broker: "XTB" },
  { id: "a2", name: "Konto BBB", broker: "XTB" },
];

function view(over: Partial<PortfolioView> = {}): PortfolioView {
  return {
    summary: {
      value: 12345.67,
      contributions: 12000,
      gain: 345.67,
      gainPct: 2.88,
      returnValue: 4.76,
      returnAnnualized: false,
      pricesAsOf: "2026-10-30",
    },
    positions: [
      { key: "BBB.PL", name: "Fundusz BBB", kind: "INSTRUMENT", value: 7000, sharePct: 56.7, gainPct: -3.8, priceDate: "2026-10-30", firstDay: "2026-03-03" },
      { key: "AAA.DE", name: "Spółka AAA", kind: "INSTRUMENT", value: 5000, sharePct: 40.5, gainPct: 10, priceDate: "2026-10-30", firstDay: "2026-03-03" },
      { key: "cash", name: "Gotówka", kind: "CASH", value: 345.67, sharePct: 2.8, gainPct: null, priceDate: null, firstDay: "2026-03-01" },
    ],
    series: [],
    warnings: [],
    empty: false,
    ...over,
  };
}

let requests: string[];
let handlers: Record<string, () => Response>;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  requests = [];
  handlers = {};
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    requests.push(url);
    const path = url.split("?")[0];
    const handler = handlers[path];
    return handler ? handler() : json({ message: "nie ma" }, 404);
  }));
});
afterEach(() => vi.unstubAllGlobals());

function renderDashboard() {
  return render(<MemoryRouter><DashboardPage /></MemoryRouter>);
}

describe("DashboardPage", () => {
  it("shows value, gain and positions in API order", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view());
    renderDashboard();
    expect(await screen.findByText("12 346 zł", { normalizer: (s) => s })).toBeInTheDocument();
    expect(screen.getByText(/\+346\s?zł/)).toBeInTheDocument();
    const items = screen.getAllByRole("listitem").map((li) => li.textContent ?? "");
    expect(items[0]).toContain("Fundusz BBB");
    expect(items[1]).toContain("Spółka AAA");
    expect(items[2]).toContain("Gotówka");
    expect(items[0]).toContain("−3,8%");
  });

  it("labels the return by returnAnnualized", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view());
    const first = renderDashboard();
    expect(await screen.findByText("wynik od początku")).toBeInTheDocument();
    first.unmount();
    handlers["/api/portfolio"] = () =>
      json(view({ summary: { ...view().summary, returnAnnualized: true } }));
    renderDashboard();
    expect(await screen.findByText("stopa ważona rocznie")).toBeInTheDocument();
  });

  it("shows a dash when the return is null", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view({ summary: { ...view().summary, returnValue: null } }));
    renderDashboard();
    expect(await screen.findByLabelText("Zwrot")).toHaveTextContent("—");
  });

  it("shows the empty state with a link to /update", async () => {
    handlers["/api/accounts"] = () => json([]);
    handlers["/api/portfolio"] = () => json(view({ empty: true, positions: [] }));
    renderDashboard();
    expect(await screen.findByText("Dodaj pierwszy eksport XTB lub zakup obligacji")).toBeInTheDocument();
    const links = screen.getAllByRole("link", { name: /Aktualizuj dane|Dodaj/ });
    expect(links.some((a) => a.getAttribute("href") === "/update")).toBe(true);
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  it("shows warnings at the top", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view({ warnings: ["Brak ceny dla AAA.DE"] }));
    renderDashboard();
    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent("Brak ceny dla AAA.DE");
    const main = status.closest("main")!;
    expect(main.firstElementChild).toBe(status);
  });

  it("requests the selected account", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view());
    renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    await userEvent.click(screen.getByRole("button", { name: "Konto BBB" }));
    await waitFor(() => expect(requests).toContain("/api/portfolio?account=a2"));
    expect(requests).toContain("/api/portfolio?account=all");
  });
});

describe("DashboardPage allocation colours", () => {
  it("keeps a position's colour after switching the account filter", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view());
    const { container } = renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    const colour = () => container.querySelector<HTMLElement>('.allocation-bar [data-key="BBB.PL"]')?.style.backgroundColor;
    const before = colour();
    expect(before).toBeTruthy();
    handlers["/api/portfolio"] = () => json(view({ positions: [view().positions[0]] }));
    await userEvent.click(screen.getByRole("button", { name: "Konto BBB" }));
    await waitFor(() => expect(requests).toContain("/api/portfolio?account=a2"));
    await waitFor(() => expect(container.querySelectorAll(".allocation-bar [data-key]")).toHaveLength(1));
    expect(colour()).toBe(before);
  });
});

describe("App", () => {
  it("shows the login view on 401", async () => {
    handlers["/api/me"] = () => json({ message: "Brak sesji" }, 401);
    render(<MemoryRouter><App /></MemoryRouter>);
    expect(await screen.findByLabelText("Hasło")).toBeInTheDocument();
  });

  it("shows the dashboard when authenticated", async () => {
    handlers["/api/me"] = () => json({ authenticated: true });
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view());
    render(<MemoryRouter><App /></MemoryRouter>);
    expect(await screen.findByText("12 346 zł", { normalizer: (s) => s })).toBeInTheDocument();
  });
});
