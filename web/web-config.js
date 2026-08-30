// Loaded only by the Vercel deploy (web/dist/index.html). Everywhere else the file 404s
// harmlessly and window.__WEBSIM__ stays undefined. It switches the dashboard to the
// serverless emulation: SOS/clear are tracked in the page and the board comes from
// /api/snapshot; there is no SSE stream and no live-phone toggle.
window.__WEBSIM__ = true;
