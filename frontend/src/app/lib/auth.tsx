import { createContext, useContext, useEffect, useState, ReactNode } from "react";
import { api, setAccessToken, setUnauthorizedHandler, refreshAccessToken } from "./apiClient";

export type Role = "ADMIN" | "MANAGER" | "SALES_REP" | "SUPPORT_AGENT" | "MARKETING";
export type AuthProviderKind = "LOCAL" | "GOOGLE" | "MICROSOFT";
export type CurrentUser = {
  id: string;
  fullName: string;
  email: string;
  role: Role;
  permissions: string[];
  avatarUrl?: string | null;
  authProvider: AuthProviderKind;
  emailVerified: boolean;
  phone?: string | null;
  jobTitle?: string | null;
  department?: string | null;
  mustChangePassword: boolean;
};

type AuthState = {
  user: CurrentUser | null;
  status: "loading" | "authenticated" | "unauthenticated";
  login: (email: string, password: string) => Promise<void>;
  signup: (organizationName: string, fullName: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
  changePassword: (oldPassword: string, newPassword: string) => Promise<void>;
};


/*
 * Whether this browser has a session worth trying to restore.
 *
 * The refresh cookie is httpOnly by design, so JavaScript cannot see it. This
 * flag is only a hint used to avoid a guaranteed-401 request on every page
 * load by a logged-out visitor — the cookie remains the actual authority, and
 * a stale hint costs nothing but the one request we were making anyway.
 *
 * OAuth is the exception: the server sets the cookie during a redirect that
 * this app never observes, so the callback returns with ?signed_in=1 to say
 * "a session was just created, go and pick it up".
 */
const SESSION_HINT_KEY = "techcrm.hasSession";

function hasProbableSession(): boolean {
  if (new URLSearchParams(window.location.search).has("signed_in")) {
    return true;
  }
  try {
    return window.localStorage.getItem(SESSION_HINT_KEY) === "1";
  } catch {
    // Private mode or blocked storage: fall back to always attempting.
    return true;
  }
}

function rememberSession(): void {
  try {
    window.localStorage.setItem(SESSION_HINT_KEY, "1");
  } catch {
    /* storage unavailable — the cookie still works, we just lose the hint */
  }
}

function clearSessionHint(): void {
  try {
    window.localStorage.removeItem(SESSION_HINT_KEY);
  } catch {
    /* ignore */
  }
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [status, setStatus] = useState<AuthState["status"]>("loading");

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      setStatus("unauthenticated");
    });

    let cancelled = false;

    // The access token only lives in memory, so a fresh page load has none —
    // the httpOnly refresh cookie is what persists a session across reloads.
    // Attempt a silent refresh first; only fall back to the login screen if
    // that fails (no cookie, or it's expired/revoked).
    //
    // Skipped entirely when we have no reason to think a session exists. The
    // refresh cookie is httpOnly so we can't read it, but we do know whether
    // this browser has ever signed in — and firing a request we expect to 401
    // on every visit by a logged-out user is just console noise.
    (async () => {
      if (!hasProbableSession()) {
        if (!cancelled) setStatus("unauthenticated");
        return;
      }
      const token = await refreshAccessToken();
      if (!token) {
        // The cookie is gone or was revoked; stop claiming a session exists.
        clearSessionHint();
        if (!cancelled) setStatus("unauthenticated");
        return;
      }
      try {
        const me = await api.get<CurrentUser>("/auth/me");
        rememberSession();
        if (!cancelled) {
          setUser(me);
          setStatus("authenticated");
        }
      } catch {
        if (!cancelled) setStatus("unauthenticated");
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const login: AuthState["login"] = async (email, password) => {
    const data = await api.post<{ accessToken: string }>("/auth/login", { email, password });
    setAccessToken(data.accessToken);
    setUser(await api.get<CurrentUser>("/auth/me"));
    rememberSession();
    setStatus("authenticated");
  };

  const signup: AuthState["signup"] = async (organizationName, fullName, email, password) => {
    const data = await api.post<{ accessToken: string }>("/auth/signup", {
      organizationName,
      fullName,
      email,
      password,
    });
    setAccessToken(data.accessToken);
    setUser(await api.get<CurrentUser>("/auth/me"));
    rememberSession();
    setStatus("authenticated");
  };

  const logout: AuthState["logout"] = async () => {
    await api.post("/auth/logout").catch(() => {});
    setAccessToken(null);
    clearSessionHint();
    setUser(null);
    setStatus("unauthenticated");
  };

  const refreshUser: AuthState["refreshUser"] = async () => {
    setUser(await api.get<CurrentUser>("/auth/me"));
  };

  const changePassword: AuthState["changePassword"] = async (oldPassword, newPassword) => {
    const data = await api.post<{ accessToken: string }>("/auth/change-password", { oldPassword, newPassword });
    setAccessToken(data.accessToken);
    setUser(await api.get<CurrentUser>("/auth/me"));
  };

  return (
    <AuthContext.Provider value={{ user, status, login, signup, logout, refreshUser, changePassword }}>{children}</AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}