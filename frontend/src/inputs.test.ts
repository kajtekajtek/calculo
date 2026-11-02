import { afterEach, describe, expect, it, vi } from "vitest";
import { parseDecimal, todayLocal } from "./inputs";

describe("parseDecimal", () => {
  it("accepts comma and dot", () => {
    expect(parseDecimal("3,5")).toBe(3.5);
    expect(parseDecimal("3.5")).toBe(3.5);
    expect(parseDecimal(" 7 ")).toBe(7);
    expect(parseDecimal("-0,25")).toBe(-0.25);
  });
  it("rejects invalid input", () => {
    for (const bad of ["", "abc", "1,2,3", "1.2.3", "3,", ",5", "1e3", "--1"]) {
      expect(parseDecimal(bad), bad).toBeNull();
    }
  });
});

describe("todayLocal", () => {
  afterEach(() => vi.useRealTimers());

  it("uses local calendar day just before and after local midnight", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(2026, 10, 2, 23, 59, 30));
    expect(todayLocal()).toBe("2026-11-02");
    vi.setSystemTime(new Date(2026, 10, 3, 0, 0, 30));
    expect(todayLocal()).toBe("2026-11-03");
  });
});
