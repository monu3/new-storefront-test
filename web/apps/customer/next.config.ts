import type { NextConfig } from 'next';

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? process.env.API_URL ?? 'http://localhost:8080';

const nextConfig: NextConfig = {
  reactStrictMode: true,

  /**
   * The API is same-origin to the browser.
   *
   * <p>Both apps talk to the backend this way. Without it a client-side `fetch('/api/...')` hits the
   * Next server, which has no such route and answers 404 — which is exactly what happened the first
   * time the account pages were run: `/api/v1/customer/wishlist` returned 404 while the
   * application itself was working perfectly.
   *
   * <p>Same-origin also sidesteps CORS for the browser's own requests, and keeps the Bearer token
   * off a cross-site request, which is a preflight away from being logged by someone else's proxy.
   */
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiUrl}/api/:path*`,
      },
    ];
  },

  // Cloudinary is the only allowed remote image host (D19). An explicit allowlist is safer
  // than a wildcard: a shop-supplied URL must not be able to make our server fetch arbitrary
  // hosts, which is the usual way an image proxy becomes an SSRF vector.
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: 'res.cloudinary.com',
        pathname: '/**',
      },
    ],
  },
  transpilePackages: [
    '@store-app/ui',
    '@store-app/theme-engine',
    '@store-app/enums',
    '@store-app/api-client',
    '@store-app/icons',
    '@store-app/state',
  ],
};

export default nextConfig;