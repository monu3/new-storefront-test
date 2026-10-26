'use client';

import { useEffect, useRef, useState } from 'react';

/**
 * An image that degrades to a placeholder instead of a broken-image icon.
 *
 * <p><b>Why this exists.</b> Every theme section rendered a bare `<img>` with no error path, so a
 * shop whose asset had been deleted, whose Cloudinary URL had expired, or whose logo pointed at a
 * host that no longer answered produced the browser's broken-image glyph on the storefront. The
 * renderer already had a carefully written placeholder for *missing* images; it simply had no path
 * for images that are present but fail, which is the common case for anything uploaded and later
 * removed.
 *
 * <p><b>Why `onError` alone is not enough.</b> This was the second, subtler half of the bug. The
 * server emits `<img>`; the browser starts fetching it while the page is still parsing, and a URL
 * that fails immediately — a deleted asset, or a host that does not resolve — errors *before*
 * React has hydrated and attached the handler. `onError` therefore never fires, and the broken
 * glyph stays on screen forever. So the mount effect re-checks the element: a *complete* image with
 * zero natural width has already failed, whether or not anybody was listening at the time.
 *
 * <p>A client component because both of those only exist in the browser. That is a real cost:
 * these sections are otherwise server-rendered. It is bounded — this component is a leaf with one
 * piece of state, and the surrounding page still renders on the server.
 *
 * <p>Both apps consume it. The Next storefront treats it as a client boundary; the Vite console's
 * theme preview ignores the directive and renders it as an ordinary component, which is what keeps
 * the preview and the live storefront showing the same thing.
 */
export function SafeImage({
  src,
  alt = '',
  className = 'h-full w-full object-cover',
  fallbackClassName = 'flex h-full w-full items-center justify-center bg-muted text-xs text-muted-foreground',
  fallbackLabel = 'No image',
  loading = 'lazy',
}: {
  src?: string | null;
  alt?: string;
  className?: string;
  fallbackClassName?: string;
  fallbackLabel?: string;
  loading?: 'lazy' | 'eager';
}) {
  const [failed, setFailed] = useState(false);
  const ref = useRef<HTMLImageElement | null>(null);

  useEffect(() => {
    const img = ref.current;
    if (!img) return;

    // An image that finished loading with no intrinsic width has already failed, and it did so
    // before this handler existed. Without this check the placeholder is unreachable for exactly
    // the failure case it was written for.
    if (img.complete && img.naturalWidth === 0) {
      setFailed(true);
    }
  }, [src]);

  if (!src || failed) {
    return (
      <div className={fallbackClassName} role="img" aria-label={alt || fallbackLabel}>
        {/* The label is inside a role="img" wrapper so a screen reader announces something
            meaningful rather than reading CSS-coloured empty space. */}
        <span aria-hidden>{fallbackLabel}</span>
      </div>
    );
  }

  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      ref={ref}
      src={src}
      alt={alt}
      loading={loading}
      className={className}
      onError={() => setFailed(true)}
    />
  );
}
