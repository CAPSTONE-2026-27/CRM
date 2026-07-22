import type { NextFunction, Request, Response } from "express";
import { verifyAccessToken, type AccessTokenPayload } from "../lib/jwt.js";
import { hasPermission, type PermissionKey } from "../lib/permissions.js";

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      auth?: AccessTokenPayload;
    }
  }
}

export function requireAuth(req: Request, res: Response, next: NextFunction) {
  const header = req.headers.authorization;
  if (!header?.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Missing bearer token" });
  }
  try {
    req.auth = verifyAccessToken(header.slice("Bearer ".length));
    next();
  } catch {
    return res.status(401).json({ error: "Invalid or expired token" });
  }
}

export function requireRole(...roles: AccessTokenPayload["role"][]) {
  return (req: Request, res: Response, next: NextFunction) => {
    if (!req.auth) return res.status(401).json({ error: "Unauthenticated" });
    if (!roles.includes(req.auth.role)) {
      return res.status(403).json({ error: "Insufficient role" });
    }
    next();
  };
}

// Gate a route on a per-user permission key. ADMIN always passes (see
// hasPermission). Used instead of requireRole for resource access so an
// admin can grant/revoke individual screens per user.
export function requirePermission(key: PermissionKey) {
  return (req: Request, res: Response, next: NextFunction) => {
    if (!req.auth) return res.status(401).json({ error: "Unauthenticated" });
    if (!hasPermission(req.auth, key)) {
      return res.status(403).json({ error: "You don't have access to this resource" });
    }
    next();
  };
}
