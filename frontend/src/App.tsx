import { useEffect, useState } from "react";
import { Route, Routes } from "react-router-dom";
import { api, onUnauthorized } from "./api";
import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import RatesPage from "./pages/RatesPage";
import UpdatePage from "./pages/UpdatePage";

type Session = "checking" | "anonymous" | "authenticated";

export default function App() {
  const [session, setSession] = useState<Session>("checking");

  useEffect(() => {
    onUnauthorized(() => setSession("anonymous"));
    // Also issues the XSRF-TOKEN cookie used by later mutating requests.
    api.get("/api/me").then(
      () => setSession("authenticated"),
      () => setSession((s) => (s === "checking" ? "anonymous" : s)),
    );
  }, []);

  if (session === "checking") return <p className="center">Ładowanie…</p>;
  if (session === "anonymous") return <LoginPage onLoggedIn={() => setSession("authenticated")} />;
  return (
    <Routes>
      <Route path="/update" element={<UpdatePage />} />
      <Route path="/rates" element={<RatesPage />} />
      <Route path="*" element={<DashboardPage />} />
    </Routes>
  );
}
