import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import RatesPage from "./RatesPage";
import { json, stubApi } from "./testing";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const rates = {
  series: [{ code: "EDO1126", firstYearRate: 6.5, margin: 2 }],
  cpi: [{ month: "2026-01", pct: 3.1 }],
  missingCpi: ["2026-02"],
};

function renderPage() {
  return render(<MemoryRouter><RatesPage /></MemoryRouter>);
}

describe("RatesPage", () => {
  it("highlights a missing CPI month with text", async () => {
    stubApi({ "GET /api/rates": () => json(rates) });
    renderPage();
    const missing = await screen.findByRole("listitem", { name: "2026-02" });
    expect(missing).toHaveTextContent("Brak odczytu");
    expect(screen.getByRole("listitem", { name: "2026-01" })).not.toHaveTextContent("Brak odczytu");
  });

  it("saves a CPI value typed with a comma", async () => {
    const calls = stubApi({
      "GET /api/rates": () => json(rates),
      "PUT /api/rates/cpi/2026-02": () => json({ month: "2026-02", pct: 3.5 }),
    });
    renderPage();
    const row = await screen.findByRole("listitem", { name: "2026-02" });
    await userEvent.type(within(row).getByLabelText("Inflacja 2026-02 (%)"), "3,5");
    await userEvent.click(within(row).getByRole("button", { name: "Zapisz" }));
    await vi.waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")!.body).toEqual({ pct: 3.5 });
  });

  it("adds a CPI month and rejects a malformed month without a request", async () => {
    const calls = stubApi({
      "GET /api/rates": () => json(rates),
      "PUT /api/rates/cpi/2026-03": () => json({ month: "2026-03", pct: 2 }),
    });
    renderPage();
    await screen.findByRole("listitem", { name: "2026-02" });
    await userEvent.type(screen.getByLabelText("Miesiąc (rrrr-MM)"), "2026-3");
    await userEvent.type(screen.getByLabelText("Nowa inflacja (%)"), "2");
    await userEvent.click(screen.getByRole("button", { name: "Dodaj odczyt" }));
    expect(screen.getByText(/Podaj miesiąc w formacie rrrr-MM/)).toBeInTheDocument();
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
    await userEvent.clear(screen.getByLabelText("Miesiąc (rrrr-MM)"));
    await userEvent.type(screen.getByLabelText("Miesiąc (rrrr-MM)"), "2026-03");
    await userEvent.click(screen.getByRole("button", { name: "Dodaj odczyt" }));
    await vi.waitFor(() => expect(calls.some((c) => c.path === "/api/rates/cpi/2026-03")).toBe(true));
  });

  it("saves a series with comma decimals", async () => {
    const calls = stubApi({
      "GET /api/rates": () => json(rates),
      "PUT /api/rates/series/EDO1126": () => json(rates.series[0]),
    });
    renderPage();
    const row = await screen.findByRole("listitem", { name: "EDO1126" });
    const first = within(row).getByLabelText("Oprocentowanie EDO1126 w pierwszym roku (%)");
    await userEvent.clear(first);
    await userEvent.type(first, "6,75");
    await userEvent.click(within(row).getByRole("button", { name: "Zapisz" }));
    await vi.waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")!.body).toEqual({ firstYearRate: 6.75, margin: 2 });
  });

  it("deletes a CPI reading after confirmation", async () => {
    const calls = stubApi({
      "GET /api/rates": () => json(rates),
      "DELETE /api/rates/cpi/2026-01": () => new Response(null, { status: 204 }),
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderPage();
    const row = await screen.findByRole("listitem", { name: "2026-01" });
    await userEvent.click(within(row).getByRole("button", { name: "Usuń" }));
    await vi.waitFor(() => expect(calls.some((c) => c.method === "DELETE")).toBe(true));
  });
});
