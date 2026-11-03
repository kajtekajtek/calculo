import { useEffect, useState } from "react";
import { api, ApiError, PriceStatus } from "../api";

const POLL_MS = 2000;
const POLL_LIMIT_MS = 120_000;

/**
 * Follows the background price sync: checks once on mount (an import may have just started one) and after each
 * refresh, polls briefly while it runs, and calls `onFinished` (which must be stable) when a followed run ends.
 */
export function usePriceSync(onFinished: () => void) {
  const [status, setStatus] = useState<PriceStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshes, setRefreshes] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = Date.now() + POLL_LIMIT_MS;
    // A refresh may have finished before the first check, so it always counts as a followed run.
    let followed = refreshes > 0;
    const poll = async () => {
      let next: PriceStatus;
      try {
        next = await api.get<PriceStatus>("/api/prices/status");
      } catch {
        // Only a convenience: the dashboard still works, it just will not update by itself.
        if (!cancelled) setStatus((s) => (s ? { ...s, running: false } : s));
        return;
      }
      if (cancelled) return;
      if (next.running && Date.now() < deadline) {
        followed = true;
        setStatus(next);
        timer = setTimeout(() => void poll(), POLL_MS);
        return;
      }
      setStatus({ ...next, running: false });
      if (followed) onFinished();
    };
    void poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [refreshes, onFinished]);

  async function refresh() {
    setError(null);
    setStatus((s) => ({ running: true, notBefore: s?.notBefore ?? null }));
    try {
      await api.post("/api/prices/refresh");
      setRefreshes((n) => n + 1);
    } catch (e) {
      setStatus((s) => (s ? { ...s, running: false } : s));
      setError(e instanceof ApiError ? e.message : "Nie udało się odświeżyć notowań.");
    }
  }

  return { running: status?.running ?? false, notBefore: status?.notBefore ?? null, error, refresh };
}
