// Server-only gateway to the Spring Boot API.
// The "server-only" import makes the build fail if this module is ever imported by a
// Client Component, so the API key can't leak into the browser bundle by accident.
import "server-only";

const BACKEND_URL = process.env.BACKEND_URL;
const BACKEND_API_KEY = process.env.BACKEND_API_KEY;

export async function backendFetch(path: string, init: RequestInit = {}): Promise<Response> {
  if (!BACKEND_URL || !BACKEND_API_KEY) {
    throw new Error("BACKEND_URL and BACKEND_API_KEY must be set on the server");
  }
  return fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: { ...(init.headers ?? {}), "X-API-Key": BACKEND_API_KEY },
    cache: "no-store",
  });
}
