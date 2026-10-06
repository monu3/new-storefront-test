import StorefrontLayout from '@/app/s/[shopSlug]/layout';
import { CheckoutView } from './checkout-view';

/** The checkout page. A client island: it talks to the quote and order endpoints directly. */
export default async function CheckoutPage({
  params,
}: {
  params: Promise<{ shopSlug: string }>;
}) {
  const { shopSlug } = await params;
  return (
    <StorefrontLayout>
      <CheckoutView shopSlug={shopSlug} />
    </StorefrontLayout>
  );
}