'use client';

import { useLayoutEffect, useRef, useState } from 'react';

import { ThemeRenderer } from '@store-app/theme-engine';
import type { StorefrontData, ThemeConfig } from '@store-app/theme-engine';

/**
 * A theme rendered at a real desktop width and then scaled down.
 *
 * <p><b>Why not render at the container's width.</b> The storefront's layout is chosen by CSS media
 * queries, and a media query asks the <em>browser</em> how wide it is — never how wide this box is.
 * A preview 390px wide inside a 1440px window therefore lays the page out for a desktop and clips
 * it: the first version of the customiser did exactly that, and the hero headline ran off the edge
 * of the "phone". A preview that shows a layout no customer can ever see is worse than no preview.
 * So the page is rendered at a real desktop width and scaled as a whole, the way a screenshot
 * would have been. `ResizeObserver` keeps the scale exact when the grid reflows.
 *
 * <p>Two shapes:
 * <ul>
 *   <li>{@code clip} — fixed frame (`aspect-[16/10]`) showing the top of the page, for gallery
 *       cards.</li>
 *   <li>{@code full} — the whole page, scaled, inside a scrollable box, for the customiser. A
 *       transformed element contributes no height to its parent, so the scaled height is measured
 *       and reserved explicitly; without that the box collapses to nothing.</li>
 * </ul>
 */
const RENDER_WIDTH = 1280;

export function ScaledPreview({
  config,
  data,
  mode = 'clip',
  className,
}: {
  config: ThemeConfig | string;
  data: StorefrontData;
  mode?: 'clip' | 'full';
  className?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(0.25);
  const [height, setHeight] = useState(0);

  useLayoutEffect(() => {
    const element = box.current;
    const content = inner.current;
    if (!element || !content) return;

    const measure = () => {
      const width = element.getBoundingClientRect().width;
      // Guard against a zero width during the first layout pass: scaling by 0 would collapse the
      // preview and leave an empty card with no explanation.
      if (width <= 0) return;
      const next = width / RENDER_WIDTH;
      setScale(next);
      // `offsetHeight` is the untransformed layout height — the only height worth reserving space
      // for. `getBoundingClientRect().height` would already include the scale.
      setHeight(content.offsetHeight * next);
    };

    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    observer.observe(content);
    return () => observer.disconnect();
  }, []);

  const frame =
    mode === 'clip'
      ? className ?? 'aspect-[16/10] w-full overflow-hidden bg-white'
      : className ?? 'max-h-[70vh] overflow-y-auto bg-white';

  return (
    <div ref={box} className={frame} aria-hidden>
      <div style={{ height: mode === 'full' ? height : undefined }}>
        <div
          ref={inner}
          style={{
            width: RENDER_WIDTH,
            transform: `scale(${scale})`,
            transformOrigin: 'top left',
          }}
        >
          {/* The same component the storefront renders — not a mock-up of it. */}
          <ThemeRenderer config={config} data={data} showIssues={false} />
        </div>
      </div>
    </div>
  );
}
