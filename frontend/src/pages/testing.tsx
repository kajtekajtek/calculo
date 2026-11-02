import { vi } from "vitest";

export interface Call { method: string; path: string; body: unknown; headers: Record<string, string> }

export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Stubs fetch with a router keyed by "METHOD /path"; returns the recorded calls. */
export function stubApi(routes: Record<string, (call: Call) => Response | Promise<Response>>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? "GET";
    const body = typeof init.body === "string" ? JSON.parse(init.body) : init.body;
    const call = { method, path: url, body, headers: (init.headers ?? {}) as Record<string, string> };
    calls.push(call);
    const route = routes[`${method} ${url}`];
    return route ? route(call) : json({ message: "nie ma" }, 404);
  }));
  return calls;
}
