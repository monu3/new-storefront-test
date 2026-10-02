import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  reactStrictMode: true,
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
