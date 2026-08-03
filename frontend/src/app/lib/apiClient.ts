// The Spring API serves everything under /api. Override with VITE_API_URL to
// point at a deployed environment.
const API_BASE_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080/api";

// Spring Security mounts the OAuth handshake at the server root
// (/oauth2/authorization/...), outside the /api prefix the REST controllers
// use — so the sign-in buttons need the bare server origin, not API_BASE_URL.
const SERVER_ORIGIN = API_BASE_URL.replace(/\/api\/?$/, "");

let accessToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

async function refreshAccessToken(): Promise<string | null> {
  const res = await fetch(`${API_BASE_URL}/auth/refresh`, { method: "POST", credentials: "include" });
  if (!res.ok) return null;
  const data = await res.json();
  accessToken = data.accessToken;
  return accessToken;
}

async function request<T>(path: string, init?: RequestInit, retried = false): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...init?.headers,
    },
  });

  if (res.status === 401 && !retried) {
    const refreshed = await refreshAccessToken();
    if (refreshed) return request<T>(path, init, true);
    onUnauthorized?.();
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error ?? `Request failed: ${res.status}`);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body: body ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: "PATCH", body: body ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
};

// Downloads an authenticated endpoint's response as a file. A plain <a href>
// or window.open can't do this — neither sends the Bearer token — so we fetch
// the body and hand the browser a temporary blob URL instead.
export async function downloadFile(path: string, fallbackFilename: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    credentials: "include",
    headers: { ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  });

  if (res.status === 401) {
    const refreshed = await refreshAccessToken();
    if (!refreshed) {
      onUnauthorized?.();
      throw new Error("Unauthorized");
    }
    return downloadFile(path, fallbackFilename);
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error ?? `Download failed: ${res.status}`);
  }

  // Prefer the server's filename from Content-Disposition when present.
  const filename =
    res.headers.get("Content-Disposition")?.match(/filename="?([^"]+)"?/)?.[1] ?? fallbackFilename;

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export type CopilotMessage = { role: "user" | "assistant"; content: string };

// Manual SSE parsing over fetch (not EventSource) since we need a Bearer
// header, which EventSource cannot send.
export async function streamCopilotChat(
  messages: CopilotMessage[],
  handlers: { onDelta: (text: string) => void; onError: (message: string) => void; onDone: () => void }
) {
  const res = await fetch(`${API_BASE_URL}/copilot/chat`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify({ messages }),
  });

  if (!res.body) {
    handlers.onError("No response stream from server");
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";

    for (const raw of events) {
      const lines = raw.split("\n");
      // The space after the field name is optional in the SSE spec and servers
      // differ — Spring's SseEmitter omits it. Match the field, then strip one
      // optional leading space from the value.
      const field = (name: string): string | undefined => {
        const line = lines.find((l) => l.startsWith(`${name}:`));
        return line === undefined ? undefined : line.slice(name.length + 1).replace(/^ /, "");
      };

      const event = field("event");
      const rawData = field("data");
      if (!event || rawData === undefined) continue;

      let data: { text?: string; message?: string };
      try {
        data = JSON.parse(rawData);
      } catch {
        continue; // a partial or malformed frame — wait for the next one
      }

      if (event === "delta") handlers.onDelta(data.text ?? "");
      else if (event === "error") handlers.onError(data.message ?? "The assistant failed.");
      else if (event === "done") handlers.onDone();
    }
  }
}

export { API_BASE_URL, SERVER_ORIGIN, refreshAccessToken };
