import { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { SeriesPoint } from "../api";
import { formatDay, formatMonth, formatPct, formatPln } from "../format";

type View = "value" | "return";
type Range = "1M" | "6M" | "1R" | "Całość";

const VIEWS: { id: View; label: string }[] = [
  { id: "value", label: "Wartość" },
  { id: "return", label: "Stopa ważona" },
];
const RANGES: Range[] = ["1M", "6M", "1R", "Całość"];
const MONTHS: Record<Range, number | null> = { "1M": 1, "6M": 6, "1R": 12, "Całość": null };
const VIEW_KEY = "calculo.chart.view";
const RANGE_KEY = "calculo.chart.range";

function readStored<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const v = localStorage.getItem(key);
    return allowed.includes(v as T) ? (v as T) : fallback;
  } catch {
    return fallback;
  }
}

function store(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* storage unavailable: choice is kept for this render only */ }
}

/** Points on or after (last day minus the range), compared as ISO strings. */
function inRange(series: SeriesPoint[], range: Range): SeriesPoint[] {
  const months = MONTHS[range];
  if (months === null || series.length === 0) return series;
  const last = new Date(`${series[series.length - 1].day}T00:00:00Z`);
  last.setUTCMonth(last.getUTCMonth() - months);
  const cutoff = last.toISOString().slice(0, 10);
  return series.filter((p) => p.day >= cutoff);
}

interface TooltipProps {
  active?: boolean;
  payload?: { payload: SeriesPoint }[];
  view: View;
}

function ChartTooltip({ active, payload, view }: TooltipProps) {
  if (!active || !payload?.length) return null;
  const p = payload[0].payload;
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-date">{formatDay(p.day)}</div>
      {view === "value" ? (
        <>
          <div><i className="swatch swatch-line" style={{ backgroundColor: "var(--cat-1)" }} aria-hidden="true" />Wartość {formatPln(p.value)}</div>
          <div><i className="swatch swatch-line" style={{ backgroundColor: "var(--cat-other)" }} aria-hidden="true" />Wpłaty {formatPln(p.contributions)}</div>
        </>
      ) : (
        <div><i className="swatch swatch-line" style={{ backgroundColor: "var(--cat-1)" }} aria-hidden="true" />Wynik od początku {formatPct(p.cumulativeReturn)}</div>
      )}
    </div>
  );
}

const axisTick = { fill: "var(--muted)", fontSize: 12 };

/** Portfolio value (with contributions) or weighted return over time, on a single axis. */
export default function ValueChart({ series }: { series: SeriesPoint[] }) {
  const [view, setView] = useState<View>(() => readStored(VIEW_KEY, ["value", "return"] as const, "value"));
  const [range, setRange] = useState<Range>(() => readStored(RANGE_KEY, RANGES, "Całość"));
  const data = useMemo(() => inRange(series, range), [series, range]);
  if (series.length === 0) return null;

  const single = data.length === 1;
  const dot = single ? { r: 4, strokeWidth: 2, stroke: "var(--surface)" } : false;
  const activeDot = { r: 4, strokeWidth: 2, stroke: "var(--surface)" };
  const isValue = view === "value";

  return (
    <section className="chart" aria-label="Wykres portfela">
      <div className="chart-controls">
        <div className="segmented" role="group" aria-label="Widok wykresu">
          {VIEWS.map((v) => (
            <button key={v.id} type="button" aria-pressed={view === v.id}
              onClick={() => { setView(v.id); store(VIEW_KEY, v.id); }}>
              {v.label}
            </button>
          ))}
        </div>
        <div className="segmented" role="group" aria-label="Zakres">
          {RANGES.map((r) => (
            <button key={r} type="button" aria-pressed={range === r}
              onClick={() => { setRange(r); store(RANGE_KEY, r); }}>
              {r}
            </button>
          ))}
        </div>
      </div>
      {isValue ? (
        <div className="chart-legend" data-testid="chart-legend">
          <span className="legend-item"><i className="swatch swatch-line" style={{ backgroundColor: "var(--cat-1)" }} aria-hidden="true" />Wartość</span>
          <span className="legend-item"><i className="swatch swatch-line" style={{ backgroundColor: "var(--cat-other)" }} aria-hidden="true" />Wpłaty</span>
        </div>
      ) : (
        <p className="label">wynik od początku</p>
      )}
      <div className="chart-plot">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke="var(--viz-grid)" strokeDasharray="" />
            <XAxis dataKey="day" tickFormatter={formatMonth} tick={axisTick} stroke="var(--viz-axis)"
              tickLine={false} minTickGap={40} />
            <YAxis tick={axisTick} stroke="var(--viz-axis)" axisLine={false} tickLine={false}
              width={isValue ? 76 : 56} domain={["auto", "auto"]}
              tickFormatter={(n: number) => (isValue ? formatPln(n) : formatPct(n))} />
            <Tooltip content={<ChartTooltip view={view} />} cursor={{ stroke: "var(--viz-axis)" }} isAnimationActive={false} />
            {isValue ? (
              <>
                <Line type="monotone" dataKey="contributions" stroke="var(--cat-other)" strokeWidth={2}
                  strokeLinecap="round" strokeLinejoin="round" dot={dot} activeDot={activeDot} isAnimationActive={false} />
                <Line type="monotone" dataKey="value" stroke="var(--cat-1)" strokeWidth={2}
                  strokeLinecap="round" strokeLinejoin="round" dot={dot} activeDot={activeDot} isAnimationActive={false} />
              </>
            ) : (
              <Line type="monotone" dataKey="cumulativeReturn" stroke="var(--cat-1)" strokeWidth={2}
                strokeLinecap="round" strokeLinejoin="round" connectNulls={false} dot={dot} activeDot={activeDot}
                isAnimationActive={false} />
            )}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </section>
  );
}
