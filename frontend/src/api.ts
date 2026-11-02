export interface Account { id: string; name: string; broker: string }

export interface Summary {
  value: number;
  contributions: number;
  gain: number;
  gainPct: number | null;
  returnValue: number | null;
  returnAnnualized: boolean;
  pricesAsOf: string | null;
}

/** kind is INSTRUMENT, BONDS or CASH; key is the ticker, "edo" or "cash". */
export interface PositionRow {
  key: string;
  name: string;
  kind: "INSTRUMENT" | "BONDS" | "CASH";
  value: number;
  sharePct: number;
  gainPct: number | null;
  priceDate: string | null;
  /** Warsaw day of the position's first activity; orders colour slots. */
  firstDay: string;
}

export interface SeriesPoint {
  day: string;
  value: number;
  contributions: number;
  cumulativeReturn: number | null;
}

export interface PortfolioView {
  summary: Summary;
  positions: PositionRow[];
  series: SeriesPoint[];
  warnings: string[];
  empty: boolean;
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public body?: unknown) {
    super(message);
  }
}

let unauthorizedHandler: () => void = () => {};

/** Registers the callback invoked when any request answers 401. */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler;
}

function csrfToken(): string | null {
  const match = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.slice("XSRF-TOKEN=".length)) : null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const multipart = body instanceof FormData;
  // For multipart the browser sets Content-Type itself, including the boundary.
  if (body !== undefined && !multipart) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    const token = csrfToken();
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  const response = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  });
  if (response.status === 401 && path !== "/api/login") unauthorizedHandler();
  if (!response.ok) {
    let message = "Coś poszło nie tak.";
    let parsed: unknown;
    try {
      parsed = await response.json();
      message = (parsed as { message?: string })?.message ?? message;
    } catch { /* body is not JSON */ }
    throw new ApiError(response.status, message, parsed);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  upload: <T>(path: string, form: FormData) => request<T>("POST", path, form),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
};

export interface ImportProblem { file: string; sheet: string; row: number; message: string }

export interface ImportPreview {
  newOperations: number;
  duplicates: number;
  problems: ImportProblem[];
  warnings: string[];
  canSave: boolean;
}

export interface BondPurchase { id: number; seriesCode: string; purchaseDate: string; quantity: number }

export interface EdoSeries { code: string; firstYearRate: number; margin: number }

export interface CpiEntry { month: string; pct: number }

export interface RatesResponse { series: EdoSeries[]; cpi: CpiEntry[]; missingCpi: string[] }

/** price is in the instrument's own currency (no currency given), null when unknown. */
export interface PurchaseRow {
  day: string;
  accountName: string;
  label: string;
  volume: number;
  price: number | null;
  amountPln: number;
  valueNow: number;
}

/** series holds the position's value and cost (in contributions) per day. */
export interface PositionDetail { row: PositionRow; purchases: PurchaseRow[]; series: SeriesPoint[] }
