import StorefrontLayout from '@/app/s/[shopSlug]/layout';
import { CartView } from './cart-view';

/** The cart page. A client island inside the storefront shell, because the cart is browser state. */
export default async function CartPage({
  params,
}: {
  params: Promise<{ shopSlug: string }>;
}) {
  const { shopSlug } = await params;
  return (
    <StorefrontLayout>
      <CartView shopSlug={shopSlug} />
    </StorefrontLayout>
  );
}