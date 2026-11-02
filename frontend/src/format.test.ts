import { describe, expect, it } from "vitest";
import { formatPct, formatPln } from "./format";

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
});
