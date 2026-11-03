import { describe, expect, it } from "vitest";
import { formatDateTime, formatPct, formatPln, priceNote } from "./format";

describe("format", () => {
  it("formats PLN with grouped thousands and a non-breaking space", () => {
    expect(formatPln(12345.67)).toBe("12 346 zł");
    expect(formatPln(7619)).toBe("7 619 zł");
  });
  it("formats percentages with sign and comma", () => {
    expect(formatPct(4.76)).toBe("+4,8%");
    expect(formatPct(-3.8)).toBe("−3,8%");
    expect(formatPct(null)).toBe("—");
  });
  it("formats an instant in Warsaw time in Polish", () => {
    // 23:30 UTC is already the next day in Warsaw.
    expect(formatDateTime("2026-11-02T23:30:00Z")).toMatch(/^3 lis 2026.*00:30$/);
  });
  it("notes the price date, or valuation at cost for an unpriced instrument only", () => {
    expect(priceNote({ kind: "INSTRUMENT", priceDate: "2026-10-30" })).toBe("cena z 30 paź 2026");
    expect(priceNote({ kind: "INSTRUMENT", priceDate: null })).toBe("wycena po koszcie");
    expect(priceNote({ kind: "BONDS", priceDate: null })).toBeNull();
    expect(priceNote({ kind: "CASH", priceDate: null })).toBeNull();
  });
});
