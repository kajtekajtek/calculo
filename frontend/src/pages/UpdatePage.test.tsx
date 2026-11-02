import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import UpdatePage from "./UpdatePage";
import { json, stubApi } from "../test-support";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/update"]}>
      <Routes>
        <Route path="/update" element={<UpdatePage />} />
        <Route path="/" element={<p>Ekran główny</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

const file = () => new File(["x"], "eksport.xlsx");
const preview = (over = {}) => ({ newOperations: 12, duplicates: 40, problems: [], warnings: [], canSave: true, ...over });

describe("UpdatePage XTB import", () => {
  it("previews, then commits the same file with CSRF header and goes home", async () => {
    document.cookie = "XSRF-TOKEN=tok123";
    const calls = stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/imports/xtb/preview": () => json(preview()),
      "POST /api/imports/xtb/commit": () => json(preview()),
    });
    renderPage();
    const input = screen.getByLabelText("Plik eksportu XTB");
    expect(input).not.toHaveAttribute("capture");
    const f = file();
    await userEvent.upload(input, f);
    expect(await screen.findByText("12 nowych, 40 już było")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Zapisz" }));
    expect(await screen.findByText("Ekran główny")).toBeInTheDocument();
    const upload = calls.filter((c) => c.path.startsWith("/api/imports"));
    expect(upload.map((c) => c.path)).toEqual(["/api/imports/xtb/preview", "/api/imports/xtb/commit"]);
    for (const c of upload) {
      expect((c.body as FormData).get("file")).toBe(f);
      expect(c.headers["X-XSRF-TOKEN"]).toBe("tok123");
      expect(c.headers["Content-Type"]).toBeUndefined();
    }
  });

  it("lists problems with row numbers and hides Zapisz when canSave is false", async () => {
    stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/imports/xtb/preview": () => json(preview({
        canSave: false,
        problems: [{ file: "a.xlsx", sheet: "Cash Operations", row: 7, message: "Nieznany typ operacji" }],
      })),
    });
    renderPage();
    await userEvent.upload(screen.getByLabelText("Plik eksportu XTB"), file());
    expect(await screen.findByText(/Wiersz 7/)).toHaveTextContent("Nieznany typ operacji");
    expect(screen.queryByRole("button", { name: "Zapisz" })).not.toBeInTheDocument();
  });

  it("shows the message of a 400 answer", async () => {
    stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/imports/xtb/preview": () => json({ message: "Nieczytelny plik." }, 400),
    });
    renderPage();
    await userEvent.upload(screen.getByLabelText("Plik eksportu XTB"), file());
    expect(await screen.findByRole("alert")).toHaveTextContent("Nieczytelny plik.");
  });

  it("keeps warnings visible and still allows saving", async () => {
    stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/imports/xtb/preview": () => json(preview({ warnings: ["Rozbieżność AAA.DE"] })),
    });
    renderPage();
    await userEvent.upload(screen.getByLabelText("Plik eksportu XTB"), file());
    expect(await screen.findByText("Rozbieżność AAA.DE")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Zapisz" })).toBeEnabled();
  });

  it("shows problems returned with a 422 on commit and keeps the warnings", async () => {
    stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/imports/xtb/preview": () => json(preview({ warnings: ["Rozbieżność AAA.DE"] })),
      "POST /api/imports/xtb/commit": () => json(preview({
        canSave: false, problems: [{ file: "a.xlsx", sheet: "Cash Operations", row: 9, message: "Zły komentarz" }],
      }), 422),
    });
    renderPage();
    await userEvent.upload(screen.getByLabelText("Plik eksportu XTB"), file());
    await userEvent.click(await screen.findByRole("button", { name: "Zapisz" }));
    expect(await screen.findByText(/Wiersz 9/)).toHaveTextContent("Zły komentarz");
    expect(screen.getByText("Rozbieżność AAA.DE")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Zapisz" })).not.toBeInTheDocument();
  });

  it("lets the same file be picked again after a 422 on commit", async () => {
    const calls = stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/imports/xtb/preview": () => json(preview()),
      "POST /api/imports/xtb/commit": () => json(preview({ canSave: false, problems: [{ file: "a", sheet: "s", row: 1, message: "Zły" }] }), 422),
    });
    renderPage();
    const f = file();
    await userEvent.upload(screen.getByLabelText("Plik eksportu XTB"), f);
    await userEvent.click(await screen.findByRole("button", { name: "Zapisz" }));
    await screen.findByText(/Wiersz 1/);
    await userEvent.upload(screen.getByLabelText("Plik eksportu XTB"), f);
    await waitFor(() => expect(calls.filter((c) => c.path.endsWith("/preview"))).toHaveLength(2));
  });

  it("reminds that the export must cover the whole history", () => {
    stubApi({ "GET /api/bonds": () => json([]) });
    renderPage();
    expect(screen.getByText("Wgraj eksport obejmujący historię od otwarcia konta.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Stopy/ })).toHaveAttribute("href", "/rates");
  });
});

describe("UpdatePage EDO form", () => {
  const bond = { id: 5, seriesCode: "EDO1126", purchaseDate: "2026-11-02", quantity: 3 };

  it("defaults to today in local time, has a numeric quantity, saves and clears", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(2026, 10, 3, 0, 0, 30));
    let list = [] as unknown[];
    const calls = stubApi({
      "GET /api/bonds": () => json(list),
      "POST /api/bonds": () => { list = [bond]; return json(bond, 201); },
    });
    renderPage();
    const date = screen.getByLabelText("Data zakupu");
    expect(date).toHaveValue("2026-11-03");
    const qty = screen.getByLabelText("Liczba sztuk");
    expect(qty).toHaveAttribute("inputmode", "numeric");
    await userEvent.type(qty, "3");
    await userEvent.click(screen.getByRole("button", { name: "Zapisz obligacje" }));
    expect(await screen.findByText(/Zapisano.*EDO1126/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST" && c.path === "/api/bonds")!.body)
      .toEqual({ purchaseDate: "2026-11-03", quantity: 3 });
    expect(screen.getByLabelText("Liczba sztuk")).toHaveValue("");
    expect(await screen.findByText(/EDO1126/, { selector: "li *, li" })).toBeInTheDocument();
  });

  it("asks for rates on 409 needsRates, keeps the entry and resends with rates", async () => {
    let n = 0;
    const calls = stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/bonds": () => (n++ === 0
        ? json({ message: "Podaj je ręcznie.", seriesCode: "EDO1126", needsRates: true }, 409)
        : json(bond, 201)),
    });
    renderPage();
    fireEvent.change(screen.getByLabelText("Data zakupu"), { target: { value: "2026-11-02" } });
    await userEvent.type(screen.getByLabelText("Liczba sztuk"), "3");
    await userEvent.click(screen.getByRole("button", { name: "Zapisz obligacje" }));
    const first = await screen.findByLabelText(/Oprocentowanie w pierwszym roku/);
    const margin = screen.getByLabelText(/Marża/);
    expect(screen.getByLabelText("Data zakupu")).toHaveValue("2026-11-02");
    expect(screen.getByLabelText("Liczba sztuk")).toHaveValue("3");
    await userEvent.type(first, "6,5");
    await userEvent.type(margin, "2");
    await userEvent.click(screen.getByRole("button", { name: "Zapisz obligacje" }));
    await screen.findByText(/Zapisano.*EDO1126/);
    const posts = calls.filter((c) => c.method === "POST" && c.path === "/api/bonds");
    expect(posts[1].body).toEqual({ purchaseDate: "2026-11-02", quantity: 3, firstYearRate: 6.5, margin: 2 });
  });

  it("shows an inline message and sends nothing for an invalid rate", async () => {
    const calls = stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/bonds": () => json({ message: "x", seriesCode: "EDO1126", needsRates: true }, 409),
    });
    renderPage();
    await userEvent.type(screen.getByLabelText("Liczba sztuk"), "3");
    await userEvent.click(screen.getByRole("button", { name: "Zapisz obligacje" }));
    await userEvent.type(await screen.findByLabelText(/Oprocentowanie w pierwszym roku/), "abc");
    await userEvent.type(screen.getByLabelText(/Marża/), "2");
    const before = calls.length;
    await userEvent.click(screen.getByRole("button", { name: "Zapisz obligacje" }));
    expect(await screen.findByText(/Oprocentowanie.*liczbą/)).toBeInTheDocument();
    expect(calls.length).toBe(before);
  });

  it("shows the message of a 400 answer and keeps the values", async () => {
    stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/bonds": () => json({ message: "Data zakupu nie może być z przyszłości." }, 400),
    });
    renderPage();
    await userEvent.type(screen.getByLabelText("Liczba sztuk"), "3");
    await userEvent.click(screen.getByRole("button", { name: "Zapisz obligacje" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Data zakupu nie może być z przyszłości.");
    expect(screen.getByLabelText("Liczba sztuk")).toHaveValue("3");
  });

  it("does not send twice on a double tap and shows a pending state", async () => {
    let release: (r: Response) => void = () => {};
    const calls = stubApi({
      "GET /api/bonds": () => json([]),
      "POST /api/bonds": () => new Promise<Response>((resolve) => { release = resolve; }),
    });
    renderPage();
    await userEvent.type(screen.getByLabelText("Liczba sztuk"), "3");
    const button = screen.getByRole("button", { name: "Zapisz obligacje" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(await screen.findByRole("button", { name: "Zapisywanie…" })).toBeDisabled();
    release(json(bond, 201));
    await screen.findByText(/Zapisano/);
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
  });

  it("edits an entry with PUT", async () => {
    const calls = stubApi({
      "GET /api/bonds": () => json([bond]),
      "PUT /api/bonds/5": () => json({ ...bond, quantity: 4 }),
    });
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: /Edytuj/ }));
    expect(screen.getByLabelText("Liczba sztuk")).toHaveValue("3");
    await userEvent.clear(screen.getByLabelText("Liczba sztuk"));
    await userEvent.type(screen.getByLabelText("Liczba sztuk"), "4");
    await userEvent.click(screen.getByRole("button", { name: "Zapisz zmiany" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")!.body).toEqual({ purchaseDate: "2026-11-02", quantity: 4 });
  });

  it("deletes only after confirmation", async () => {
    let list = [bond];
    const calls = stubApi({
      "GET /api/bonds": () => json(list),
      "DELETE /api/bonds/5": () => { list = []; return new Response(null, { status: 204 }); },
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    renderPage();
    const del = await screen.findByRole("button", { name: /Usuń/ });
    await userEvent.click(del);
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    await userEvent.click(del);
    await waitFor(() => expect(screen.queryByRole("button", { name: /Usuń/ })).not.toBeInTheDocument());
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(calls.filter((c) => c.method === "DELETE")).toHaveLength(1);
  });
});
