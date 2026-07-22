import "dotenv/config";
import express from "express";
import cors from "cors";
import cookieParser from "cookie-parser";
import session from "express-session";
import { prisma } from "./lib/prisma.js";
import { errorHandler } from "./middleware/errorHandler.js";
import { passport } from "./lib/passport.js";
import { authRouter } from "./routes/auth.js";
import { usersRouter } from "./routes/users.js";
import { accountsRouter, contactsRouter, leadsRouter, dealsRouter, casesRouter, campaignsRouter } from "./routes/crm.js";
import { rpaBotsRouter, rpaBotRunsRouter } from "./routes/rpaBots.js";
import { workflowsRouter } from "./routes/workflows.js";
import { auditLogRouter } from "./routes/auditLog.js";
import { analyticsRouter } from "./routes/analytics.js";
import { copilotRouter } from "./routes/copilot.js";

const app = express();

app.use(cors({ origin: process.env.CORS_ORIGIN ?? "http://localhost:5173", credentials: true }));
// Raised from Express's 100kb default — profile photo uploads are sent as base64 data URIs.
app.use(express.json({ limit: "5mb" }));
app.use(cookieParser());

// Session is only used transiently to hold Passport's OAuth "state" (CSRF
// protection) during the Google/Microsoft handshake — we don't do
// session-based login (no passport.session()); our own JWTs handle that.
app.use(
  session({
    secret: process.env.SESSION_SECRET ?? "dev-only-oauth-state-secret",
    resave: false,
    saveUninitialized: false,
    cookie: { maxAge: 5 * 60 * 1000 },
  })
);
app.use(passport.initialize());

app.get("/health", async (_req, res) => {
  await prisma.$queryRaw`SELECT 1`;
  res.json({ status: "ok" });
});

app.use("/auth", authRouter);
app.use("/users", usersRouter);
app.use("/accounts", accountsRouter);
app.use("/contacts", contactsRouter);
app.use("/leads", leadsRouter);
app.use("/deals", dealsRouter);
app.use("/cases", casesRouter);
app.use("/campaigns", campaignsRouter);
app.use("/rpa-bots", rpaBotsRouter);
app.use("/rpa-bot-runs", rpaBotRunsRouter);
app.use("/workflows", workflowsRouter);
app.use("/audit-log", auditLogRouter);
app.use("/analytics", analyticsRouter);
app.use("/copilot", copilotRouter);

app.use(errorHandler);

const port = Number(process.env.PORT ?? 4000);
app.listen(port, () => {
  console.log(`server listening on :${port}`);
});
