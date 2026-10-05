export async function GET(request: Request) {
  const headers = { 'Cache-Control': 'no-store' };
  const upstream = process.env.BACKEND_URL;
  if (!upstream) return Response.json({ ready: false }, { status: 503, headers });
  try {
    const response = await fetch(upstream.replace(/\/$/, '') + '/actuator/health', {
      cache: 'no-store', redirect: 'error',
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(4000)]),
    });
    const health = await response.json() as { status?: unknown } | null;
    const ready = response.ok && health?.status === 'UP';
    return Response.json({ ready }, { status: ready ? 200 : 503, headers });
  } catch {
    return Response.json({ ready: false }, { status: 503, headers });
  }
}
