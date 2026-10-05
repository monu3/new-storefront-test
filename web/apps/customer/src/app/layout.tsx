import type { ReactNode } from 'react';
import '@store-app/ui/styles.css';
import './globals.css';

import { CustomerShell } from './customer-shell';

/**
 * The customer app's root layout.
 *
 * <p>Wired through {@link CustomerShell} rather than leaving each page to build its own chrome. The
 * header has to know whether somebody is signed in on every single page, and a per-page decision is
 * how three of five pages end up with a "Sign in" link while the other two have none.
 *
 * <p><b>No session cookie here.</b> This app is server-rendered against the API; the token lives in
 * the browser for the API calls the client makes, and the server reads it per request. Putting one
 * in a layout would create a second source of truth for "is this person signed in".
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh bg-background antialiased">
        <CustomerShell>{children}</CustomerShell>
      </body>
    </html>
  );
}