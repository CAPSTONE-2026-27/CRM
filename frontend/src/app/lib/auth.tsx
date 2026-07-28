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
    (async () => {
      const token = await refreshAccessToken();
      if (!token) {
        if (!cancelled) setStatus("unauthenticated");
        return;
      }
      try {
        const me = await api.get<CurrentUser>("/auth/me");
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
    setStatus("authenticated");
  };

  const logout: AuthState["logout"] = async () => {
    await api.post("/auth/logout").catch(() => {});
    setAccessToken(null);
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