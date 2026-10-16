
const PUBLIC_READ_ROUTES = [
  /^\/api\/incidents$/,
  /^\/api\/incidents\/[^/]+$/,
  /^\/api\/incidents\/[^/]+\/events$/,
  /^\/api\/incidents\/[^/]+\/proposals$/,
  /^\/api\/proposals\/[^/]+$/,
  /^\/api\/replay\/evaluations$/,
  /^\/api\/replay\/stats$/,
];

function isPublicReadRoute(path) {
  return PUBLIC_READ_ROUTES.some((pattern) => pattern.test(path));
}

function hasOperatorCredential(req) {
  const authorization = req.headers.authorization;
  const operatorKey = req.headers["x-operator-key"];

  // Never treat the shared X-API-Key as an operator credential.
  return (
    (typeof authorization === "string" &&
      /^Bearer\s+\S+$/i.test(authorization)) ||
    (typeof operatorKey === "string" && operatorKey.trim().length > 0)
  );
}

export default async function handler(req, res) {
  const backend = process.env.AEGIS_BACKEND_URL;
  const apiKey = process.env.AEGIS_API_KEY;

  if (!backend || !apiKey) {
    return res.status(503).json({
      error: "Backend proxy is not configured",
    });
  }

  const method = (req.method || "GET").toUpperCase();
  const requestUrl = new URL(req.url || "/", "https://proxy.invalid");
  const path = requestUrl.pathname;

  // This function is only an API proxy, not a general-purpose URL proxy.
  if (!path.startsWith("/api/") && path !== "/api") {
    return res.status(404).json({ error: "Not found" });
  }

  const isSignIn =
    method === "POST" && path === "/api/auth/session";

  const publicRead =
    method === "GET" && isPublicReadRoute(path);

  const protectedRequest = !publicRead && !isSignIn;

  if (protectedRequest && !hasOperatorCredential(req)) {
    return res.status(401).json({
      error: "Operator sign-in required",
    });
  }

  // Only explicitly supported methods are forwarded.
  if (!["GET", "HEAD", "POST", "PUT", "DELETE"].includes(method)) {
    return res.status(405).json({ error: "Method not allowed" });
  }

  try {
    const backendUrl = new URL(backend);

    if (!["http:", "https:"].includes(backendUrl.protocol)) {
      return res.status(500).json({ error: "Invalid backend configuration" });
    }

    const target = new URL(`${path}${requestUrl.search}`, backendUrl);

    // Prevent a configured backend path or malformed URL from changing hosts.
    if (target.origin !== backendUrl.origin) {
      return res.status(400).json({ error: "Invalid target" });
    }

    const headers = new Headers();

    for (const [name, value] of Object.entries(req.headers)) {
      const lower = name.toLowerCase();

      if (
        value &&
        ![
          "host",
          "connection",
          "content-length",
          "x-api-key",
          "x-operator-key",
          "transfer-encoding",
        ].includes(lower)
      ) {
        headers.set(name, Array.isArray(value) ? value.join(", ") : value);
      }
    }

    // Operator sign-in must reach the backend with the operator's own key.
    if (isSignIn) {
      const operatorKey = req.headers["x-operator-key"];

      if (typeof operatorKey !== "string" || !operatorKey.trim()) {
        return res.status(401).json({
          error: "Operator key required",
        });
      }

      headers.set("X-Operator-Key", operatorKey);
    }

    // Inject the shared client key only on the server.
    headers.set("X-API-Key", apiKey);

    let body;

    if (!["GET", "HEAD"].includes(method) && req.body != null) {
      body =
        typeof req.body === "string" || Buffer.isBuffer(req.body)
          ? req.body
          : JSON.stringify(req.body);

      if (typeof req.body === "object" && !Buffer.isBuffer(req.body)) {
        headers.set("Content-Type", "application/json");
      }
    }

    const upstream = await fetch(target, {
      method,
      headers,
      body,
      redirect: "manual",
    });

    res.status(upstream.status);

    upstream.headers.forEach((value, name) => {
      if (
        ![
          "connection",
          "content-encoding",
          "content-length",
          "transfer-encoding",
          "set-cookie",
        ].includes(name.toLowerCase())
      ) {
        res.setHeader(name, value);
      }
    });

    return res.send(Buffer.from(await upstream.arrayBuffer()));
  } catch {
    return res.status(502).json({
      error: "Could not reach the Aegis backend",
    });
  }
}
