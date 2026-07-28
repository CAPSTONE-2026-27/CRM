import passport from "passport";
import { Strategy as GoogleStrategy, type Profile as GoogleProfile } from "passport-google-oauth20";
import { Strategy as MicrosoftStrategy, type Profile as MicrosoftProfile } from "passport-microsoft";
import { prisma } from "./prisma.js";
import { defaultPermissionsForRole } from "./permissions.js";
import type { User } from "@prisma/client";

type OAuthProvider = "GOOGLE" | "MICROSOFT";

// Shared account-resolution logic for both providers:
// 1. Returning OAuth user — matched by (authProvider, providerAccountId).
// 2. Existing LOCAL user with the same email — link the provider onto that
//    account (password login keeps working; the account also becomes
//    reachable via this provider from now on) rather than creating a duplicate.
// 3. Brand-new user — auto-create an organization for them (same as the
//    local signup flow) and make them its admin, since OAuth carries no
//    invite/org context of its own.
async function resolveOAuthUser(
  provider: OAuthProvider,
  providerAccountId: string,
  email: string | undefined,
  displayName: string,
  avatarUrl: string | undefined
): Promise<User> {
  const existingByProvider = await prisma.user.findFirst({ where: { authProvider: provider, providerAccountId } });
  if (existingByProvider) return existingByProvider;

  if (email) {
    const existingByEmail = await prisma.user.findFirst({ where: { email, authProvider: "LOCAL" } });
    if (existingByEmail) {
      return prisma.user.update({
        where: { id: existingByEmail.id },
        data: { authProvider: provider, providerAccountId, avatarUrl: avatarUrl ?? existingByEmail.avatarUrl, emailVerified: true },
      });
    }
  }

  if (!email) {
    throw new Error(`${provider} account has no email — cannot create a new user without one`);
  }

  return prisma.$transaction(async (tx) => {
    const organization = await tx.organization.create({ data: { name: `${displayName}'s Organization` } });
    const user = await tx.user.create({
      data: {
        organizationId: organization.id,
        email,
        fullName: displayName,
        passwordHash: null,
        authProvider: provider,
        providerAccountId,
        avatarUrl,
        emailVerified: true,
        role: "ADMIN",
        permissions: defaultPermissionsForRole("ADMIN"),
      },
    });
    await tx.rpaBot.createMany({
      data: [
        { organizationId: organization.id, name: "Lead enrichment bot", platform: "UIPATH", triggerSource: "Lead created", status: "REGISTERED" },
        { organizationId: organization.id, name: "Follow-up sequencing bot", platform: "UIPATH", triggerSource: "Scheduled (hourly)", status: "REGISTERED" },
        { organizationId: organization.id, name: "Case routing bot", platform: "UIPATH", triggerSource: "Case created", status: "REGISTERED" },
      ],
    });
    return user;
  });
}

// Strategies only register when their credentials are configured — both
// constructors throw synchronously otherwise, which would crash the whole
// process on boot. Routes check these flags before calling
// passport.authenticate() with a strategy name that was never registered
// (which throws too) and return a clear error instead.
export const googleOAuthEnabled = Boolean(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET);
export const microsoftOAuthEnabled = Boolean(process.env.MICROSOFT_CLIENT_ID && process.env.MICROSOFT_CLIENT_SECRET);

if (googleOAuthEnabled) {
  passport.use(
    new GoogleStrategy(
      {
        clientID: process.env.GOOGLE_CLIENT_ID!,
        clientSecret: process.env.GOOGLE_CLIENT_SECRET!,
        callbackURL: "/auth/google/callback",
        scope: ["profile", "email"],
      },
      (_accessToken, _refreshToken, profile: GoogleProfile, done) => {
        resolveOAuthUser("GOOGLE", profile.id, profile.emails?.[0]?.value, profile.displayName, profile.photos?.[0]?.value)
          .then((user) => done(null, user))
          .catch(done);
      }
    )
  );
} else {
  console.warn("GOOGLE_CLIENT_ID/SECRET not set — Google login is disabled");
}

if (microsoftOAuthEnabled) {
  passport.use(
    new MicrosoftStrategy(
      {
        clientID: process.env.MICROSOFT_CLIENT_ID!,
        clientSecret: process.env.MICROSOFT_CLIENT_SECRET!,
        callbackURL: "/auth/microsoft/callback",
        scope: ["user.read"],
        tenant: process.env.MICROSOFT_TENANT_ID ?? "common",
      },
      (_accessToken, _refreshToken, profile: MicrosoftProfile, done) => {
        resolveOAuthUser("MICROSOFT", profile.id, profile.emails?.[0]?.value, profile.displayName, profile.photos?.[0]?.value)
          .then((user) => done(null, user))
          .catch(done);
      }
    )
  );
} else {
  console.warn("MICROSOFT_CLIENT_ID/SECRET not set — Microsoft login is disabled");
}

export { passport };
