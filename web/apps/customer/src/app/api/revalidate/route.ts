import { revalidateTag } from 'next/cache';

import type { NextRequest } from 'next/server';

/**
 * Purges the storefront's cached HTML after the API changes a theme.
 *
 * <p>The storefront is server-rendered with a 60-second revalidation window (D1: product pages must
 * be indexable and share-previewable, which means HTML, not a client fetch). That window is right
 * for traffic — it keeps a busy storefront off the API — but wrong at the moment an owner clicks
 * "Publish changes".
 *
 * <p>Without this, the sequence was: publish, then open the storefront, see the *old* theme, and
 * conclude the publish had failed. It had not; the page was simply up to a minute stale, and
 * nothing on screen said so. The console said "Live. Your storefront is updated." while the link
 * the owner is about to click shows the previous design — which is the least convincing possible
 * confirmation.
 *
 * <p>So the API calls this after a successful save-and-publish, and the owner's own browser shows
 * the change immediately.
 *
 * <p>Guarded by a shared secret rather than open: this endpoint can force a server to re-render
 * arbitrary shop pages, so an unauthenticated caller must not be able to trigger it. Compared as a
 * timing-safe match so the secret cannot be recovered a character at a time.
 */
export async function POST(request: NextRequest): Promise<Response> {
  const expected = process.env.STOREFRONT_REVALIDATE_SECRET ?? '';
  const supplied = request.headers.get('x-revalidate-secret') ?? '';

  // With no secret configured the endpoint is inert rather than open. A development machine that
  // has not set one should not silently expose an unauthenticated purge.
  if (expected.length === 0) {
    return Response.json(
      { revalidated: false, reason: 'STOREFRONT_REVALIDATE_SECRET is not configured' },
      { status: 503 },
    );
  }

  if (!timingSafeEqual(supplied, expected)) {
    return Response.json({ revalidated: false, reason: 'invalid secret' }, { status: 401 });
  }

  const body = (await request.json().catch(() => ({}))) as { shopSlug?: string };
  const slug = body.shopSlug;
  if (!slug) {
    return Response.json({ revalidated: false, reason: 'shopSlug is required' }, { status: 400 });
  }

  // Both tags are cleared: the storefront page and the theme payload behind it. Clearing only one
  // would leave the other serving the previous design, which is the same confusing half-state.
  revalidateTag(`storefront:${slug}`);
  revalidateTag(`theme:${slug}`);

  return Response.json({ revalidated: true, shopSlug: slug, at: new Date().toISOString() });
}

/** Constant-time string comparison, so the secret cannot be guessed from response timing. */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}