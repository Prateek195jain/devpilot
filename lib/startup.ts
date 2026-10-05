import { auth, type User } from './devpilot-api';

export const CONNECTING_MESSAGE = 'Getting DevPilot ready… The first visit after inactivity may take about a minute. Please keep this page open—we’ll connect automatically.';
export type StartupState = {
  phase: 'connecting' | 'restoring' | 'ready' | 'unavailable';
  user: User | null;
  sessionMessage: string;
};
const initial: StartupState = { phase: 'connecting', user: null, sessionMessage: '' };

function pause(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    signal.throwIfAborted();
    const cancel = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', cancel); resolve(); }, ms);
    signal.addEventListener('abort', cancel, { once: true });
  });
}

// Only GET readiness checks are retried. The deadline includes requests and delays.
export async function waitForBackend(signal: AbortSignal, options: {
  budget?: number; now?: () => number; sleep?: typeof pause; check?: (signal: AbortSignal) => Promise<boolean>;
} = {}) {
  const now = options.now ?? Date.now;
  const deadline = now() + (options.budget ?? 120000);
  const sleep = options.sleep ?? pause;
  const check = options.check ?? (async (signal: AbortSignal) => {
    const response = await fetch('/api/readiness', { cache: 'no-store', signal });
    const result = await response.json() as { ready?: unknown } | null;
    return response.ok && result?.ready === true;
  });
  let delay = 1000;
  while (now() < deadline) {
    signal.throwIfAborted();
    try {
      const ready = await check(AbortSignal.any([signal, AbortSignal.timeout(Math.min(5000, deadline - now()))]));
      signal.throwIfAborted();
      if (ready && now() < deadline) return;
    } catch { signal.throwIfAborted(); }
    const remaining = deadline - now();
    if (remaining <= 0) break;
    await sleep(Math.min(delay, remaining), signal);
    delay = Math.min(delay * 2, 10000);
  }
  throw new Error('Readiness deadline exceeded');
}

export function createStartup(dependencies = { wait: waitForBackend, restore: () => auth.restore() }) {
  let state = initial;
  const listeners = new Set<() => void>();
  let controller: AbortController | undefined;
  // Keep the single rotation alive across navigation; aborting it could lose a rotated cookie.
  let restoration: Promise<User | null> | undefined;
  let revision = 0;
  const update = (patch: Partial<StartupState>) => {
    state = { ...state, ...patch };
    listeners.forEach(listener => listener());
  };
  const start = () => {
    if (controller || state.phase === 'ready') return;
    const current = new AbortController();
    controller = current;
    const version = revision;
    update({ phase: 'connecting' });
    void (async () => {
      try {
        await dependencies.wait(current.signal);
        if (current.signal.aborted || version !== revision) return;
        update({ phase: 'restoring' });
        restoration ??= dependencies.restore();
        try {
          const user = await restoration;
          if (!current.signal.aborted && version === revision) update({ user, sessionMessage: '', phase: 'ready' });
        } catch {
          if (!current.signal.aborted && version === revision) update({ phase: 'ready', sessionMessage: 'We couldn’t restore your session. Your saved session has not been cleared. You can sign in again.' });
        }
      } catch {
        if (!current.signal.aborted && version === revision) update({ phase: 'unavailable' });
      } finally {
        if (controller === current) controller = undefined;
      }
    })();
  };
  return {
    getSnapshot: () => state,
    getServerSnapshot: () => initial,
    subscribe(listener: () => void) {
      listeners.add(listener);
      start();
      return () => {
        listeners.delete(listener);
        // React Strict Mode immediately resubscribes; share that in-flight check.
        queueMicrotask(() => {
          if (!listeners.size) { controller?.abort(); controller = undefined; }
        });
      };
    },
    retry: start,
    setUser(user: User | null) {
      revision++;
      controller?.abort(); controller = undefined;
      restoration = Promise.resolve(user);
      update({ user, phase: 'ready', sessionMessage: '' });
    },
  };
}
export const startup = createStartup();
