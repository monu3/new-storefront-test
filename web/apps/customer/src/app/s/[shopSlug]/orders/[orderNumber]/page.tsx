import { OrderConfirmation } from './confirmation';

/**
 * Order confirmation.
 *
 * <p>Reads the order the checkout page stashed, rather than calling the account's customer-scoped
 * endpoint. Guest checkout is allowed (D50), so the person who just placed this order has no token
 * and that endpoint would answer 401 — leaving a customer staring at an empty shell immediately
 * after a successful purchase.
 */
export default async function OrderPage({
  params,
}: {
  params: Promise<{ shopSlug: string; orderNumber: string }>;
}) {
  const { shopSlug, orderNumber } = await params;
  return <OrderConfirmation shopSlug={shopSlug} orderNumber={orderNumber} />;
}
