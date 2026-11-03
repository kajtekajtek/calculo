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

const dateTimeFormat = new Intl.DateTimeFormat("pl-PL", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "Europe/Warsaw" });

/** ISO instant in Warsaw time, e.g. "3 lis 2026, 00:30". */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso));
}

/** Where an instrument's value comes from; null for positions that have no market price (bonds, cash). */
export function priceNote({ kind, priceDate }: { kind: string; priceDate: string | null }): string | null {
  if (kind !== "INSTRUMENT") return null;
  return priceDate === null ? "wycena po koszcie" : `cena z ${formatDay(priceDate)}`;
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
