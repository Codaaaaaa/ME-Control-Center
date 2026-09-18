import { useEffect, useRef, useState } from 'react';
import { useStatusQuery } from './useStatusQuery';

/** The entry script an HTML page loads, e.g. `/assets/index-BpNXg1br.js`. */
export function entryScript(html: string): string | null {
  const match = /<script[^>]*type="module"[^>]*src="([^"]+)"/.exec(html);
  return match ? match[1]! : null;
}

function loadedEntryScript(): string | null {
  const script = document.querySelector<HTMLScriptElement>('script[type="module"][src]');
  return script ? new URL(script.src, window.location.href).pathname : null;
}

/**
 * Whether the server now serves a different web UI than the one running in this tab. A single-page app keeps
 * its code until reloaded, so after a mod update an open tab would otherwise keep running the old version.
 * Checked only when the server has restarted (its start time changed).
 */
export function useUpdateAvailable(): boolean {
  const status = useStatusQuery();
  const startedAt = status.data?.mecc.startedAt;
  const firstStart = useRef<string | undefined>(undefined);
  const [available, setAvailable] = useState(false);

  useEffect(() => {
    if (!startedAt) return;
    if (firstStart.current === undefined) {
      firstStart.current = startedAt;
      return;
    }
    if (startedAt === firstStart.current || available) return;
    const loaded = loadedEntryScript();
    const controller = new AbortController();
    fetch('/', { cache: 'no-cache', signal: controller.signal })
      .then((response) => (response.ok ? response.text() : ''))
      .then((html) => {
        const served = entryScript(html);
        if (served && loaded && served !== loaded) setAvailable(true);
      })
      .catch(() => {
        // Checked again after the next restart.
      });
    return () => controller.abort();
  }, [startedAt, available]);

  return available;
}
