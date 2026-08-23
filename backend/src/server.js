/**
 * Antigravity YouTube Extraction Backend
 *
 * SECURITY MODEL
 * --------------
 * This server is a LAN-only developer tool by default. It proxies yt-dlp for
 * search/stream extraction and has NO authentication unless one is configured.
 *
 * Hardening applied in the forensic remediation pass:
 *  - Binds 127.0.0.1 by default. Set HOST=0.0.0.0 explicitly to serve your
 *    LAN, and understand that anyone on that network can then use it.
 *  - Optional shared-secret auth: set AUTH_TOKEN; clients must send header
 *    "x-auth-token". The Android client does not currently attach it - enable
 *    only when pairing with an updated client or a reverse proxy.
 *  - CORS is locked to http://localhost:* / emulator origin unless CORS_ORIGIN
 *    lists additional allowed origins (comma separated).
 *  - videoId strictly validated (^[\w-]{11}$); query length capped.
 *  - Per-IP rate limiting on extraction endpoints.
 *  - Error responses are sanitized: internal yt-dlp messages are logged, never
 *    returned to the client.
 */

const express = require("express");
const cors = require("cors");
const youtubedl = require("youtube-dl-exec");

const app = express();

const PORT = parseInt(process.env.PORT || "3000", 10);
const HOST = process.env.HOST || "127.0.0.1";
const AUTH_TOKEN = process.env.AUTH_TOKEN || "";
const MAX_QUERY_LENGTH = 200;
const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;

// ---------------------------------------------------------------------------
// CORS allowlist
// ---------------------------------------------------------------------------

const defaultOrigins = ["http://localhost", "http://10.0.2.2"];
const extraOrigins = (process.env.CORS_ORIGIN || "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

app.use(
  cors({
    origin(origin, cb) {
      // Allow same-origin/no-origin tools (curl) and localhost dev origins.
      if (!origin) return cb(null, true);
      const allowed =
        defaultOrigins.some((o) => origin.startsWith(o)) ||
        extraOrigins.some((o) => origin === o);
      return allowed ? cb(null, true) : cb(null, false); // silently deny
    },
  })
);

app.use(express.json({ limit: "64kb" }));

// ---------------------------------------------------------------------------
// Minimal per-IP sliding-window rate limiter (no external deps)
// ---------------------------------------------------------------------------

const WINDOW_MS = 60_000;
const MAX_REQUESTS_PER_WINDOW = 30;
const hits = new Map(); // ip -> [timestamps]

function rateLimit(req, res, next) {
  const ip = req.ip || req.socket.remoteAddress || "unknown";
  const now = Date.now();
  let arr = hits.get(ip);
  if (!arr) {
    arr = [];
    hits.set(ip, arr);
  }
  while (arr.length && now - arr[0] > WINDOW_MS) arr.shift();
  if (arr.length >= MAX_REQUESTS_PER_WINDOW) {
    return res.status(429).json({ error: "Rate limit exceeded. Try again later." });
  }
  arr.push(now);
  next();
}

// Periodic cleanup so idle IPs do not leak memory.
setInterval(() => {
  const now = Date.now();
  for (const [ip, arr] of hits) {
    if (!arr.length || now - arr[arr.length - 1] > WINDOW_MS * 5) hits.delete(ip);
  }
}, WINDOW_MS).unref();

// ---------------------------------------------------------------------------
// Middleware
// ---------------------------------------------------------------------------

app.use(rateLimit);

if (AUTH_TOKEN) {
  app.use((req, res, next) => {
    if (req.get("x-auth-token") !== AUTH_TOKEN) {
      return res.status(401).json({ error: "Unauthorized" });
    }
    next();
  });
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

app.get("/api/search", async (req, res) => {
  const rawQuery = Array.isArray(req.query.q) ? req.query.q[0] : req.query.q;
  const query = typeof rawQuery === "string" ? rawQuery.trim().slice(0, MAX_QUERY_LENGTH) : "";
  if (!query) {
    return res.status(400).json({ error: "Query parameter 'q' is required" });
  }

  try {
    const searchResults = await youtubedl(`ytsearch10:${query}`, {
      dumpSingleJson: true,
      noWarnings: true,
      noCallHome: true,
      preferFreeFormats: true,
      youtubeSkipDashManifest: true,
    });

    const entries = searchResults.entries || [searchResults];
    const results = entries.map((item) => ({
      id: item.id,
      title: item.title || item.fulltitle || "Unknown Track",
      artist: item.uploader || item.channel || "YouTube",
      duration: item.duration || 0,
      thumbnail: item.thumbnail || (item.thumbnails?.[0]?.url ?? ""),
    }));

    res.json({ query, results });
  } catch (error) {
    // Internal detail stays server-side only.
    console.error("Search failure:", error.message);
    res.status(502).json({ error: "Upstream extraction failed. Try again later." });
  }
});

app.get("/api/stream", async (req, res) => {
  const rawId = Array.isArray(req.query.id) ? req.query.id[0] : req.query.id;
  const videoId = typeof rawId === "string" ? rawId.trim() : "";
  if (!videoId) {
    return res.status(400).json({ error: "Query parameter 'id' is required" });
  }
  if (!VIDEO_ID_RE.test(videoId)) {
    return res.status(400).json({ error: "Malformed video id" });
  }

  try {
    const videoUrl = `https://www.youtube.com/watch?v=${videoId}`;
    const info = await youtubedl(videoUrl, {
      dumpSingleJson: true,
      noWarnings: true,
      format: "bestaudio/best",
    });

    res.json({
      id: info.id || videoId,
      streamUrl: info.url || (info.formats?.[0]?.url ?? ""),
      title: info.title || info.fulltitle || "Online Track",
      artist: info.uploader || info.channel || "YouTube",
      duration: info.duration || 0,
      thumbnail: info.thumbnail || "",
    });
  } catch (error) {
    console.error("Stream failure:", error.message);
    res.status(502).json({ error: "Upstream extraction failed. Try again later." });
  }
});

// 404 + error handlers keep responses uniform.
app.use((req, res) => res.status(404).json({ error: "Not found" }));
app.use((err, req, res, next) => {
  console.error("Unhandled failure:", err.message);
  res.status(500).json({ error: "Internal server error" });
});

const server = app.listen(PORT, HOST, () => {
  console.log(`Antigravity backend listening on http://${HOST}:${PORT}`);
  if (HOST === "127.0.0.1") {
    console.log("LAN mode off (default). For Android device testing run:");
    console.log(`  HOST=0.0.0.0 PORT=${PORT} npm start`);
  }
  console.log(AUTH_TOKEN ? "Auth: ENABLED (x-auth-token required)" : "Auth: DISABLED");
});

// Bound slowloris-style clients.
server.headersTimeout = 20_000;
server.requestTimeout = 60_000;
server.keepAliveTimeout = 15_000;

module.exports = { app, server };
