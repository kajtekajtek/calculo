import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PositionRow } from "../api";
import { colorFor } from "../palette";
import AllocationBar from "./AllocationBar";

function row(key: string, value: number, name = `Poz ${key}`): PositionRow {
  return { key, name, kind: "INSTRUMENT", value, sharePct: value, gainPct: null, priceDate: null };
}

const all = ["AAA.DE", "BBB.PL", "cash", "edo"];

describe("colorFor", () => {
  it("depends on the key, not on the position in the list", () => {
    expect(colorFor("BBB.PL", all)).toBe(colorFor("BBB.PL", [...all].reverse()));
    expect(colorFor("AAA.DE", all)).not.toBe(colorFor("BBB.PL", all));
  });
  it("gives neutral to keys past the eighth slot", () => {
    const keys = "ABCDEFGHIJ".split("").map((c) => `${c}.X`);
    expect(colorFor("J.X", keys)).toBe("var(--cat-other)");
    expect(colorFor("H.X", keys)).toBe("var(--cat-8)");
  });
});

describe("AllocationBar", () => {
  it("keeps a position's colour after the list is filtered", () => {
    const full = [row("AAA.DE", 50), row("BBB.PL", 30), row("cash", 20)];
    const first = render(<AllocationBar positions={full} allKeys={all} />);
    const before = first.container.querySelector<HTMLElement>('[data-key="BBB.PL"]')!.style.backgroundColor;
    first.unmount();
    const second = render(<AllocationBar positions={[row("BBB.PL", 100)]} allKeys={all} />);
    const after = second.container.querySelector<HTMLElement>('[data-key="BBB.PL"]')!.style.backgroundColor;
    expect(after).toBe(before);
    expect(after).toBe(colorFor("BBB.PL", all));
  });

  it("folds the smallest into Inne beyond eight positions", () => {
    const keys = "ABCDEFGHIJ".split("").map((c) => `${c}.X`);
    const positions = keys.map((k, i) => row(k, 100 - i * 5)); // A largest, J smallest
    const { container } = render(<AllocationBar positions={positions} />);
    expect(container.querySelectorAll("[data-key]")).toHaveLength(9);
    const other = container.querySelector<HTMLElement>('[data-key="__other"]')!;
    expect(other.style.backgroundColor).toBe("var(--cat-other)");
    const legend = screen.getByTestId("allocation-legend");
    expect(within(legend).getByText("Inne")).toBeInTheDocument();
    expect(within(legend).getByText("115,0%")).toBeInTheDocument(); // I (60) + J (55)
  });

  it("lists names and shares in the legend, without numbers on segments", () => {
    const positions = [row("AAA.DE", 60, "Spółka AAA"), row("BBB.PL", 40, "Fundusz BBB")];
    const { container } = render(<AllocationBar positions={positions} />);
    const legend = screen.getByTestId("allocation-legend");
    expect(legend).toHaveTextContent("Spółka AAA");
    expect(legend).toHaveTextContent("60,0%");
    expect(legend).toHaveTextContent("Fundusz BBB");
    expect(legend).toHaveTextContent("40,0%");
    for (const seg of container.querySelectorAll("[data-key]")) expect(seg.textContent).toBe("");
  });
});
