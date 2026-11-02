import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PositionRow } from "../api";
import { colorFor, colorKeys } from "../palette";
import AllocationBar from "./AllocationBar";

function row(key: string, value: number, name = `Poz ${key}`, firstDay = "2026-01-01"): PositionRow {
  return { key, name, kind: "INSTRUMENT", value, sharePct: value, gainPct: null, priceDate: null, firstDay };
}

const all = ["AAA.DE", "BBB.PL", "cash", "edo"]; // already in colour order

describe("colorFor", () => {
  it("assigns slots in the order of the key list", () => {
    expect(colorFor("AAA.DE", all)).toBe("var(--cat-1)");
    expect(colorFor("edo", all)).toBe("var(--cat-4)");
  });
  it("gives neutral to keys past the eighth slot", () => {
    const keys = "ABCDEFGHIJ".split("").map((c) => `${c}.X`);
    expect(colorFor("J.X", keys)).toBe("var(--cat-other)");
    expect(colorFor("H.X", keys)).toBe("var(--cat-8)");
    expect(colorFor("unknown", keys)).toBe("var(--cat-other)");
  });
});

describe("colorKeys", () => {
  const base = [row("BBB.PL", 10, "b", "2026-01-05"), row("cash", 5, "c", "2026-01-01"), row("AAA.DE", 7, "a", "2026-02-01")];

  it("orders by first day, then key", () => {
    expect(colorKeys(base)).toEqual(["cash", "BBB.PL", "AAA.DE"]);
    expect(colorKeys([row("ZZZ", 1, "z", "2026-01-01"), row("CCC", 1, "c", "2026-01-01")])).toEqual(["CCC", "ZZZ"]);
  });

  it("leaves existing colours unchanged when a key with a later first day joins", () => {
    const before = colorKeys(base);
    const after = colorKeys([...base, row("AAB.PL", 99, "new", "2026-06-01")]);
    for (const key of before) expect(colorFor(key, after)).toBe(colorFor(key, before));
    expect(colorFor("AAB.PL", after)).toBe("var(--cat-4)");
  });

  it("does not let an alphabetically earlier but later-started key displace others", () => {
    const keys = colorKeys([...base, row("AAA.AA", 1, "x", "2026-09-09")]);
    expect(keys[keys.length - 1]).toBe("AAA.AA");
    expect(colorFor("cash", keys)).toBe("var(--cat-1)");
  });

  it("keeps a colour when the position's value grows past others", () => {
    const keys = colorKeys(base);
    const grown = base.map((p) => (p.key === "cash" ? { ...p, value: 1000 } : p));
    expect(colorKeys(grown)).toEqual(keys);
    const { container } = render(<AllocationBar positions={grown} allKeys={colorKeys(grown)} />);
    expect(container.querySelector<HTMLElement>('[data-key="cash"]')!.style.backgroundColor).toBe(colorFor("cash", keys));
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
