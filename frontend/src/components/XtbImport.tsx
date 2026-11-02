import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, ApiError, type ImportPreview, type ImportProblem } from "../api";

function Problems({ problems }: { problems: ImportProblem[] }) {
  return (
    <ul className="error">
      {problems.map((p, i) => (
        <li key={i}>Wiersz {p.row} ({p.file}, {p.sheet}): {p.message}</li>
      ))}
    </ul>
  );
}

/** Two-step XTB import: preview the chosen file, then re-send the same file to save it. */
export default function XtbImport() {
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  // Warnings come from the preview and stay visible until the user leaves the page.
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  async function run(action: () => Promise<void>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await action();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  function form(f: File) {
    const data = new FormData();
    data.append("file", f);
    return data;
  }

  function choose(f: File | undefined) {
    setPreview(null);
    setWarnings([]);
    setFile(f ?? null);
    if (!f) return;
    void run(async () => {
      try {
        const result = await api.upload<ImportPreview>("/api/imports/xtb/preview", form(f));
        setPreview(result);
        setWarnings(result.warnings);
      } catch (e) {
        setError(e instanceof ApiError ? e.message : "Nie udało się odczytać pliku.");
      }
    });
  }

  function commit() {
    if (!file) return;
    void run(async () => {
      try {
        await api.upload<ImportPreview>("/api/imports/xtb/commit", form(file));
        navigate("/");
      } catch (e) {
        if (e instanceof ApiError && e.status === 422 && e.body) {
          setPreview(e.body as ImportPreview);
        } else {
          setError(e instanceof ApiError ? e.message : "Nie udało się zapisać pliku.");
        }
      }
    });
  }

  return (
    <section className="card">
      <h2>Eksport XTB</h2>
      <p className="label">Wgraj eksport obejmujący historię od otwarcia konta.</p>
      <label>
        Plik eksportu XTB
        <br />
        <input type="file" accept=".xlsx,.zip" onChange={(e) => choose(e.target.files?.[0])} disabled={busy} />
      </label>
      {busy && !preview && <p>Sprawdzanie pliku…</p>}
      {error && <p role="alert" className="error">{error}</p>}
      {warnings.length > 0 && (
        <div className="warnings">
          {warnings.map((w, i) => <p key={i}>{w}</p>)}
        </div>
      )}
      {preview && (
        <div>
          <p>{preview.newOperations} nowych, {preview.duplicates} już było</p>
          {preview.problems.length > 0 && <Problems problems={preview.problems} />}
          {preview.canSave && (
            <button type="button" onClick={commit} disabled={busy}>{busy ? "Zapisywanie…" : "Zapisz"}</button>
          )}
        </div>
      )}
    </section>
  );
}
