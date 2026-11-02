/** Parses a decimal typed by a Polish user: accepts both "3,5" and "3.5". Returns null when invalid. */
export function parseDecimal(text: string): number | null {
  const normalized = text.trim().replace(",", ".");
  if (!/^-?\d+(\.\d+)?$/.test(normalized)) return null;
  return Number(normalized);
}

/** Today in the browser's local time as yyyy-MM-dd (toISOString would shift the day near midnight). */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
