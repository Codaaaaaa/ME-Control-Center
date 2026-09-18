import { useState } from 'react';
import { iconUrl } from '../../api/resources';

/** One automatic retry per icon: a load the browser aborted must not turn into a permanent placeholder. */
const MAX_ATTEMPTS = 2;

/**
 * Resource icon, loaded from the server's icon endpoint. Falls back to a neutral placeholder when the
 * server cannot render one (spec section 20, Tier 4): the name and the grid stay intact.
 */
export function ResourceIcon({
  iconKey,
  assetVersion,
  size,
  alt = '',
}: {
  iconKey: string;
  assetVersion: string;
  size: number;
  alt?: string;
}) {
  // Failures are remembered per source, never per tile: the grid reuses its tiles as storage changes, so
  // a tile that showed a missing icon a moment ago is usually showing a different resource now.
  const [failure, setFailure] = useState<{ src: string; attempts: number } | null>(null);
  const src = assetVersion ? iconUrl(iconKey, assetVersion) : '';
  const attempts = failure !== null && failure.src === src ? failure.attempts : 0;

  if (!src || attempts >= MAX_ATTEMPTS) {
    return <PlaceholderIcon size={size} />;
  }
  return (
    <img
      // A fresh element per source: changing src on an <img> that is still loading aborts that load and
      // reports it as an error, which is exactly what happens when a tile is reused while scrolling or
      // when the storage contents shift.
      key={`${src}|${attempts}`}
      className="resource-icon"
      src={src}
      width={size}
      height={size}
      alt={alt}
      decoding="async"
      draggable={false}
      onError={() => setFailure({ src, attempts: attempts + 1 })}
    />
  );
}

function PlaceholderIcon({ size }: { size: number }) {
  return (
    <svg className="resource-icon" width={size} height={size} viewBox="0 0 16 16" aria-hidden="true">
      <rect x="2.5" y="2.5" width="11" height="11" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1" opacity="0.5" />
      <path d="M6 6.2c0-1 .9-1.7 2-1.7s2 .7 2 1.7c0 1.3-1.7 1.3-1.7 2.6M8 11.4h.01" fill="none" stroke="currentColor"
        strokeWidth="1.2" strokeLinecap="round" opacity="0.6" />
    </svg>
  );
}
