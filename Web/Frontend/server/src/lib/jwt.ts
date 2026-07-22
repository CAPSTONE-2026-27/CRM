import jwt, { type SignOptions } from "jsonwebtoken";

export type AccessTokenPayload = {
  sub: string; // userId
  organizationId: string;
  role: "ADMIN" | "MANAGER" | "SALES_REP" | "SUPPORT_AGENT";
  // Per-screen access keys, snapshotted at token-mint time. Edits take effect
  // on the next login/refresh (access-token TTL is 15m).
  permissions: string[];
};

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required env var: ${name}`);
  return value;
}

export function signAccessToken(payload: AccessTokenPayload): string {
  return jwt.sign(payload, requireEnv("JWT_ACCESS_SECRET"), {
    expiresIn: (process.env.JWT_ACCESS_TTL ?? "15m") as SignOptions["expiresIn"],
  });
}

export function signRefreshToken(userId: string): string {
  return jwt.sign({ sub: userId }, requireEnv("JWT_REFRESH_SECRET"), {
    expiresIn: (process.env.JWT_REFRESH_TTL ?? "7d") as SignOptions["expiresIn"],
  });
}

export function verifyAccessToken(token: string): AccessTokenPayload {
  return jwt.verify(token, requireEnv("JWT_ACCESS_SECRET")) as AccessTokenPayload;
}

export function verifyRefreshToken(token: string): { sub: string } {
  return jwt.verify(token, requireEnv("JWT_REFRESH_SECRET")) as { sub: string };
}
