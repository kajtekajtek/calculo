import { FormEvent, useState } from "react";
import { ApiError, api } from "../api";

export default function LoginPage({ onLoggedIn }: { onLoggedIn: () => void }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/login", { password });
      onLoggedIn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Nie udało się zalogować.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page login">
      <h1>Calculo</h1>
      <form onSubmit={submit}>
        <label htmlFor="password">Hasło</label>
        <input id="password" type="password" autoComplete="current-password" autoFocus
          value={password} onChange={(e) => setPassword(e.target.value)} />
        {error && <p role="alert" className="error">{error}</p>}
        <button type="submit" disabled={busy || password === ""}>Zaloguj</button>
      </form>
    </main>
  );
}
