import { Suspense } from 'react';

import { CustomerOrders } from '../components';

/**
 * A Suspense boundary is required, not decorative.
 *
 * <p>Next statically prerenders this route, and a component calling `useSearchParams` (which the
 * order list does, to keep the page in the URL) has no value to read during that pass. Without a
 * boundary the build fails with "prerendering page /account/orders" rather than at runtime.
 *
 * <p>The fallback is a skeleton rather than a spinner, so the page does not visibly jump once the
 * real content arrives.
 */
export default function OrdersPage() {
  return (
    <Suspense fallback={<div className="h-64 animate-pulse rounded-xl bg-muted" />}>
      <CustomerOrders />
    </Suspense>
  );
}
