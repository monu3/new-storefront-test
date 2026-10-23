'use client';

import { useState } from 'react';

import { Button } from '@store-app/ui';

import { auth } from '../lib/auth';

/**
 * "Create an account" — hands off to Keycloak's own registration form.
 *
 * <p><b>A component, because PKCE cannot be done from a link.</b> The landing page is a
 * server-rendered, statically generated page, which is the right choice for it and rules out calling
 * the adapter directly. So the button lives here instead and the page keeps its HTML-first shape.
 *
 * <p>What the button deliberately does not do is navigate to a URL this project assembled by hand.
 * Both realms require PKCE, so a registration link with no `code_challenge` is refused by Keycloak
 * with a 302 straight back to the app carrying
 * `error=invalid_request&error_description=Missing parameter: code_challenge_method` — the browser
 * lands on its own error page with nothing saying the link was at fault. The verifier has to be
 * minted here, kept, and presented again at the token exchange, and `keycloak-js` already does all
 * three. Building the URL by hand cannot.
 *
 * <p>The destination is `/account`, where a new customer has somewhere obvious to be.
 */
export function RegisterButton({ variant = 'outline' }: { variant?: 'default' | 'outline' }) {
  const [busy, setBusy] = useState(false);

  async function register() {
    setBusy(true);
    try {
      await auth.register('/account');
    } catch {
      // Falling back to the plain link is better than a dead button: it will still be refused by
      // Keycloak, but the person sees the sign-in page rather than a spinner that never stops.
      window.location.href = '/login';
      setBusy(false);
    }
  }

  return (
    <Button
      variant={variant}
      className="inline-flex h-11 items-center rounded-lg px-6"
      disabled={busy}
      onClick={() => void register()}
    >
      {busy ? 'Redirecting…' : 'Create an account'}
    </Button>
  );
}