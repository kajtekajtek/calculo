import { render, screen, waitFor, within } from "@testing-library/react";
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
let posts: string[];
let handlers: Record<string, (url: string) => Response>;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  requests = [];
  posts = [];
  handlers = {};
  vi.stubGlobal("fetch", vi.fn(async (url: string, init: RequestInit = {}) => {
    requests.push(url);
    if (init.method === "POST") posts.push(url);
    const path = url.split("?")[0];
    const handler = handlers[path];
    return handler ? handler(url) : json({ message: "nie ma" }, 404);
  }));
});
afterEach(() => vi.unstubAllGlobals());

function renderDashboard(path = "/") {
  return render(<MemoryRouter initialEntries={[path]}><DashboardPage /></MemoryRouter>);
}

const unpriced = { key: "CCC.PL", name: "Fundusz CCC", kind: "INSTRUMENT", value: 100, sharePct: 1, gainPct: 0, priceDate: null, firstDay: "2026-03-04" } as const;

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

  it("shows the API's percent return as is: 10.0 is +10,0%", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view({ summary: { ...view().summary, returnValue: 10.0 } }));
    renderDashboard();
    expect(await screen.findByLabelText("Zwrot")).toHaveTextContent("+10,0%");
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
    handlers["/api/portfolio"] = (url) => json(url.endsWith("account=all") ? view() : view({ positions: [view().positions[0]] }));
    await userEvent.click(screen.getByRole("button", { name: "Konto BBB" }));
    await waitFor(() => expect(requests).toContain("/api/portfolio?account=a2"));
    await waitFor(() => expect(container.querySelectorAll(".allocation-bar [data-key]")).toHaveLength(1));
    expect(colour()).toBe(before);
  });

  it("uses the colour order of the all view when mounted on a filtered account", async () => {
    handlers["/api/accounts"] = () => json(accounts);
    handlers["/api/portfolio"] = () => json(view());
    const all = renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    const expected = all.container.querySelector<HTMLElement>('.allocation-bar [data-key="BBB.PL"]')?.style.backgroundColor;
    expect(expected).toBe("var(--cat-3)"); // cash, AAA.DE, then BBB.PL
    all.unmount();
    requests = [];

    handlers["/api/portfolio"] = (url) => json(url.endsWith("account=all") ? view() : view({ positions: [view().positions[0]] }));
    const { container } = renderDashboard("/?account=a2");
    await waitFor(() => expect(container.querySelector<HTMLElement>('.allocation-bar [data-key="BBB.PL"]')?.style.backgroundColor).toBe(expected));
    expect(requests).toContain("/api/portfolio?account=a2");
    expect(requests).toContain("/api/portfolio?account=all");
  });
});

describe("DashboardPage price freshness", () => {
  it("shows each position's price date, valuation at cost, and nothing for cash", async () => {
    handlers["/api/portfolio"] = () => json(view({ positions: [...view().positions, unpriced] }));
    renderDashboard();
    const list = await screen.findByRole("list", { name: "Pozycje" });
    const items = within(list).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("cena z 30 paź 2026");
    expect(items[2]).not.toHaveTextContent("cena z");
    expect(items[2]).not.toHaveTextContent("wycena po koszcie");
    expect(items[3]).toHaveTextContent("wycena po koszcie");
  });

  it("gives the date in Polish in the header when everything is priced", async () => {
    handlers["/api/portfolio"] = () => json(view());
    renderDashboard();
    expect(await screen.findByRole("region", { name: "Podsumowanie" })).toHaveTextContent("Ceny z dnia 30 paź 2026");
  });

  it("does not imply freshness in the header when a position is unpriced", async () => {
    handlers["/api/portfolio"] = () => json(view({ positions: [...view().positions, unpriced] }));
    renderDashboard();
    const header = await screen.findByRole("region", { name: "Podsumowanie" });
    expect(header).not.toHaveTextContent("Ceny z dnia");
    expect(header).toHaveTextContent("Bez ceny: 1 poz. (wycena po koszcie)");
  });

  it("renders no share when sharePct is null", async () => {
    const noShare = view().positions.map((p) => ({ ...p, sharePct: null }));
    handlers["/api/portfolio"] = () => json(view({ positions: noShare }));
    const { container } = renderDashboard();
    await screen.findByRole("list", { name: "Pozycje" });
    expect(container.querySelector(".positions .share")).toBeNull();
    expect(container.querySelector(".legend-share")).toBeNull();
  });
});

describe("DashboardPage price refresh", () => {
  const value = (summaryValue: number) => view({ summary: { ...view().summary, value: summaryValue } });

  it("posts the refresh, shows progress and reloads the portfolio when the sync ends", async () => {
    let running = false;
    let total = 12345.67;
    handlers["/api/portfolio"] = () => json(value(total));
    handlers["/api/prices/status"] = () => json({ running, notBefore: null });
    handlers["/api/prices/refresh"] = () => { running = true; return new Response(null, { status: 202 }); };
    renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    await userEvent.click(screen.getByRole("button", { name: "Odśwież notowania" }));
    expect(posts).toEqual(["/api/prices/refresh"]);
    expect(await screen.findByRole("button", { name: "Odświeżanie…" })).toBeDisabled();
    total = 20000;
    running = false;
    expect(await screen.findByText("20 000 zł", { normalizer: (s) => s }, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Odśwież notowania" })).toBeEnabled();
  });

  it("polls a sync already running on mount, as after a first import, and updates without a reload", async () => {
    let running = true;
    let total = 12345.67;
    handlers["/api/portfolio"] = () => json(value(total));
    handlers["/api/prices/status"] = () => json({ running, notBefore: null });
    renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    total = 20000;
    running = false;
    expect(await screen.findByText("20 000 zł", { normalizer: (s) => s }, { timeout: 5000 })).toBeInTheDocument();
    expect(posts).toEqual([]);
  });

  it("does not reload or poll when nothing is running", async () => {
    handlers["/api/portfolio"] = () => json(view());
    handlers["/api/prices/status"] = () => json({ running: false, notBefore: null });
    renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    await waitFor(() => expect(requests.filter((r) => r === "/api/prices/status")).toHaveLength(1));
    expect(requests.filter((r) => r === "/api/portfolio?account=all")).toHaveLength(1);
  });

  it("shows until when quotes are on hold after a rate limit", async () => {
    handlers["/api/portfolio"] = () => json(view());
    handlers["/api/prices/status"] = () => json({ running: false, notBefore: "2026-11-02T23:30:00Z" });
    renderDashboard();
    expect(await screen.findByText(/nie wcześniej niż 3 lis 2026.*00:30/)).toBeInTheDocument();
  });

  it("shows a failed refresh and allows another try", async () => {
    handlers["/api/portfolio"] = () => json(view());
    handlers["/api/prices/status"] = () => json({ running: false, notBefore: null });
    handlers["/api/prices/refresh"] = () => json({ message: "Odmowa" }, 403);
    renderDashboard();
    await screen.findByText("12 346 zł", { normalizer: (s) => s });
    await userEvent.click(screen.getByRole("button", { name: "Odśwież notowania" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Odmowa");
    expect(screen.getByRole("button", { name: "Odśwież notowania" })).toBeEnabled();
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

  it("logs out and returns to the login view", async () => {
    handlers["/api/me"] = () => json({ authenticated: true });
    handlers["/api/portfolio"] = () => json(view());
    handlers["/api/logout"] = () => new Response(null, { status: 204 });
    render(<MemoryRouter><App /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "Wyloguj" }));
    expect(await screen.findByLabelText("Hasło")).toBeInTheDocument();
    expect(posts).toEqual(["/api/logout"]);
  });

  it("stays logged in and says so when logging out fails", async () => {
    handlers["/api/me"] = () => json({ authenticated: true });
    handlers["/api/portfolio"] = () => json(view());
    handlers["/api/logout"] = () => json({ message: "Odmowa" }, 403);
    render(<MemoryRouter><App /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("button", { name: "Wyloguj" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Nie udało się wylogować");
    expect(screen.queryByLabelText("Hasło")).not.toBeInTheDocument();
  });
});
