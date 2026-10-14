// Optionaler CORS-Proxy für BTR Roadtrip (Cloudflare Worker, kostenloser Plan reicht).
// Nur nötig, wenn der Login in der Web-App mit "Keine Verbindung / CORS" scheitert.
// Deploy: dash.cloudflare.com → Workers & Pages → Create → Worker → diesen Code einfügen → Deploy.
// Dann die Worker-URL (z. B. https://btr-proxy.<name>.workers.dev) in der App unter "Erweitert" eintragen.

const UPSTREAM = "https://bushtalkradio.com";
const ALLOWED_PATHS = ["/api/authenticate", "/api/track"];
// Optional einschränken, z. B. "https://deinname.github.io"
const ALLOWED_ORIGIN = "*";

const cors = {
  "Access-Control-Allow-Origin": ALLOWED_ORIGIN,
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-BTR-CLIENT-NAME, X-BTR-CLIENT-VERSION",
  "Access-Control-Max-Age": "86400",
};

export default {
  async fetch(request) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });

    const url = new URL(request.url);
    if (request.method !== "POST" || !ALLOWED_PATHS.includes(url.pathname)) {
      return new Response("Not found", { status: 404, headers: cors });
    }

    const headers = new Headers();
    for (const h of ["Content-Type", "Authorization", "X-BTR-CLIENT-NAME", "X-BTR-CLIENT-VERSION"]) {
      const v = request.headers.get(h);
      if (v) headers.set(h, v);
    }

    const upstream = await fetch(UPSTREAM + url.pathname, {
      method: "POST",
      headers,
      body: await request.text(),
    });

    const out = new Headers(cors);
    out.set("Content-Type", upstream.headers.get("Content-Type") || "application/json");
    return new Response(upstream.body, { status: upstream.status, headers: out });
  },
};
