import { Suspense } from 'react';

import { CustomerOrderDetail } from '../../../../account/components';

/**
 * Four levels up: page.tsx sits at app/s/[shopSlug]/orders/[orderNumber]/, so `../../../..` is app/.
 *
 * <p>Suspense is required because the detail component reads route params during prerendering; see
 * the note on the orders page.
 */
export default function OrderPage() {
  return (
    <Suspense fallback={<div className="h-64 animate-pulse rounded-xl bg-muted" />}>
      <CustomerOrderDetail />
    </Suspense>
  );
}
