import { useEffect, useState } from "react";
import { Route, Routes } from "react-router-dom";
import { api, onUnauthorized } from "./api";
import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import PositionPage from "./pages/PositionPage";
import RatesPage from "./pages/RatesPage";
import UpdatePage from "./pages/UpdatePage";

type Session = "checking" | "anonymous" | "authenticated";

export default function App() {
  const [session, setSession] = useState<Session>("checking");
  const [logoutFailed, setLogoutFailed] = useState(false);

  useEffect(() => {
    onUnauthorized(() => setSession("anonymous"));
    // Also issues the XSRF-TOKEN cookie used by later mutating requests.
    api.get("/api/me").then(
      () => setSession("authenticated"),
      () => setSession((s) => (s === "checking" ? "anonymous" : s)),
    );
  }, []);

  async function logout() {
    setLogoutFailed(false);
    try {
      await api.post("/api/logout");
      setSession("anonymous");
    } catch {
      setLogoutFailed(true);
    }
  }

  if (session === "checking") return <p className="center">Ładowanie…</p>;
  if (session === "anonymous") return <LoginPage onLoggedIn={() => setSession("authenticated")} />;
  return (
    <>
      <div className="app-bar">
        {logoutFailed && <p role="alert" className="error">Nie udało się wylogować.</p>}
        <button type="button" onClick={() => void logout()}>Wyloguj</button>
      </div>
      <Routes>
        <Route path="/update" element={<UpdatePage />} />
        <Route path="/rates" element={<RatesPage />} />
        <Route path="/positions/:key" element={<PositionPage />} />
        <Route path="*" element={<DashboardPage />} />
      </Routes>
    </>
  );
}
