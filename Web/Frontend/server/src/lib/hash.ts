import { createHash } from "node:crypto";

// Refresh tokens are high-entropy JWTs, not low-entropy secrets, so a fast
// deterministic hash (for equality lookup) is appropriate — unlike passwords,
// which use bcrypt in the auth routes.
export function sha256Hex(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}
