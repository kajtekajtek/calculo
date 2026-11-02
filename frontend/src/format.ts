const NBSP = " ";
const MINUS = "−";

const plnNumber = new Intl.NumberFormat("pl-PL", { maximumFractionDigits: 0, useGrouping: "always" as unknown as boolean });
const pctNumber = new Intl.NumberFormat("pl-PL", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** Whole zloty with thousands grouped by a non-breaking space, e.g. "12 346 zł". */
export function formatPln(value: number): string {
  return `${plnNumber.format(value).replace(/[\s ]/g, NBSP)}${NBSP}zł`;
}

/** Like formatPln but with an explicit sign for gains and losses. */
export function formatSignedPln(value: number): string {
  const rounded = Math.round(value);
  if (rounded === 0) return formatPln(0);
  return rounded > 0 ? `+${formatPln(rounded)}` : `${MINUS}${formatPln(-rounded)}`;
}

/** Percentage with one decimal, comma and an explicit sign; null becomes a dash. */
export function formatPct(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const text = pctNumber.format(Math.abs(value));
  if (text === pctNumber.format(0)) return `${text}%`;
  return `${value < 0 ? MINUS : "+"}${text}%`;
}

/** Unsigned percentage, for shares of the portfolio. */
export function formatShare(value: number): string {
  return `${pctNumber.format(value)}%`;
}

const dateFormat = new Intl.DateTimeFormat("pl-PL", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const monthFormat = new Intl.DateTimeFormat("pl-PL", { month: "short", year: "2-digit", timeZone: "UTC" });

/** ISO day (yyyy-mm-dd) as a Polish date, e.g. "2 lis 2026". */
export function formatDay(iso: string): string {
  return dateFormat.format(new Date(`${iso}T00:00:00Z`));
}

/** ISO day as a short month label for axis ticks, e.g. "lis 26". */
export function formatMonth(iso: string): string {
  return monthFormat.format(new Date(`${iso}T00:00:00Z`));
}

const decimalNumber = new Intl.NumberFormat("pl-PL", { maximumFractionDigits: 6 });

/** Plain decimal without a unit (volumes, instrument prices), e.g. "100,5". */
export function formatDecimal(value: number): string {
  return decimalNumber.format(value).replace(/[\s ]/g, NBSP);
}
