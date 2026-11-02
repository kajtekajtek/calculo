import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SeriesPoint } from "../api";
import ValueChart from "./ValueChart";

function pt(day: string, value: number, contributions: number, cumulativeReturn: number | null): SeriesPoint {
  return { day, value, contributions, cumulativeReturn };
}

const series: SeriesPoint[] = [
  pt("2025-01-01", 1000, 1000, 0),
  pt("2026-03-01", 1100, 1000, 10),
  pt("2026-08-01", 1300, 1200, null),
  pt("2026-09-15", 1500, 1200, 25),
  pt("2026-11-01", 1520, 1200, 26.7),
];

const lines = (c: HTMLElement) => c.querySelectorAll(".recharts-line");

beforeEach(() => localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe("ValueChart", () => {
  it("defaults to the value view with two series and a legend", () => {
    const { container } = render(<ValueChart series={series} />);
    expect(lines(container)).toHaveLength(2);
    const legend = screen.getByTestId("chart-legend");
    expect(legend).toHaveTextContent("Wartość");
    expect(legend).toHaveTextContent("Wpłaty");
    expect(screen.getByRole("button", { name: "Wartość" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Całość" })).toHaveAttribute("aria-pressed", "true");
  });

  it("switches to the weighted return view and remembers the choice", async () => {
    const { container, unmount } = render(<ValueChart series={series} />);
    await userEvent.click(screen.getByRole("button", { name: "Stopa ważona" }));
    expect(lines(container)).toHaveLength(1);
    expect(screen.getByText("wynik od początku")).toBeInTheDocument();
    expect(container.textContent).toContain("%");
    expect(localStorage.getItem("calculo.chart.view")).toBe("return");
    unmount();
    render(<ValueChart series={series} />);
    expect(screen.getByRole("button", { name: "Stopa ważona" })).toHaveAttribute("aria-pressed", "true");
  });

  it("leaves a gap for null returns instead of drawing zero", async () => {
    const { container } = render(<ValueChart series={series} />);
    await userEvent.click(screen.getByRole("button", { name: "Stopa ważona" }));
    const d = container.querySelector(".recharts-line-curve")!.getAttribute("d")!;
    expect(d.match(/M/g)!.length).toBeGreaterThan(1);
  });

  it("filters by range counted from the last point", async () => {
    const { container } = render(<ValueChart series={series} />);
    expect(container.querySelectorAll(".recharts-line-dots circle, .recharts-dot").length).toBe(0);
    await userEvent.click(screen.getByRole("button", { name: "1M" }));
    // 1M from 2026-11-01 keeps only 2026-11-01 (a single point shows a marker)
    expect(container.querySelectorAll(".recharts-dot").length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole("button", { name: "6M" }));
    expect(screen.getByRole("button", { name: "6M" })).toHaveAttribute("aria-pressed", "true");
    expect(container.querySelectorAll(".recharts-dot").length).toBe(0);
    expect(localStorage.getItem("calculo.chart.range")).toBe("6M");
  });

  it("renders a single point with a marker and no error", () => {
    const { container } = render(<ValueChart series={[pt("2026-11-01", 1000, 1000, 0)]} />);
    expect(lines(container)).toHaveLength(2);
    expect(container.querySelectorAll(".recharts-dot").length).toBeGreaterThan(0);
  });

  it("works when localStorage throws", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("denied"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("denied"); });
    const { container } = render(<ValueChart series={series} />);
    await userEvent.click(screen.getByRole("button", { name: "Stopa ważona" }));
    expect(lines(container)).toHaveLength(1);
  });

  it("renders nothing for an empty series", () => {
    const { container } = render(<ValueChart series={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
