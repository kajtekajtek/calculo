import { Link } from "react-router-dom";
import EdoForm from "../components/EdoForm";
import XtbImport from "../components/XtbImport";

export default function UpdatePage() {
  return (
    <main className="page">
      <h1>Aktualizacja danych</h1>
      <p><Link to="/">Wróć do portfela</Link> · <Link to="/rates">Stopy i inflacja</Link></p>
      <XtbImport />
      <EdoForm />
    </main>
  );
}
