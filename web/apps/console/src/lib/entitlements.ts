import type { EntitlementDenial } from '@store-app/api-client';

/**
 * Reads a refused feature into something a screen can render an upgrade prompt from.
 *
 * <p>The server answers an entitlement refusal with the feature, the numbers where there are limits,
 * and the plan that would unblock it. A screen that renders only `detail` has thrown all of that
 * away and is left with a wall — and the shop owner reading it has no idea that a purchase is the
 * remedy.
 *
 * <p><b>Returns null for anything that is not an entitlement refusal.</b> A 403 from the tenant
 * guard means "this is not your shop", and offering an upgrade for that would be nonsense — so the
 * two are not conflated, which is why they are not the same exception on the server either.
 */
export async function readEntitlementDenial(
  response: Response,
): Promise<EntitlementDenial | null> {
  try {
    const body = (await response.json()) as {
      code?: string;
      detail?: string;
      feature?: string;
      upgradeTo?: string | null;
      limit?: number;
      currentCount?: number;
      adding?: number;
    };

    if (body.code !== 'ENTITLEMENT_DENIED' || !body.feature) {
      // Not ours. The body has been consumed, so a caller wanting the generic message has to rely
      // on the status text -- which is why this reads the body itself rather than taking a parsed
      // problem from elsewhere.
      return null;
    }

    return {
      feature: body.feature,
      upgradeTo: body.upgradeTo ?? null,
      limit: body.limit ?? null,
      currentCount: body.currentCount ?? null,
      adding: body.adding ?? null,
      message: body.detail ?? 'That is not in your plan.',
    };
  } catch {
    return null;
  }
}

/**
 * Turns a feature key into words an owner can read.
 *
 * <p>`max_products` is a database column name. Showing it verbatim in a purchase prompt is how a
 * shop concludes the platform does not speak English.
 */
export function featureLabel(feature: string): string {
  const labels: Record<string, string> = {
    max_products: 'Products',
    remove_commission: 'No commission',
    use_paid_templates: 'Paid templates',
    custom_css: 'Custom CSS',
    custom_domain: 'Custom domain',
    discount_codes: 'Discount codes',
    csv_import: 'CSV import',
    priority_support: 'Priority support',
    abandoned_cart_recovery: 'Abandoned-cart reminders',
  };
  return labels[feature] ?? feature.replace(/_/g, ' ');
}

/** "Up to 500 products", or "Paid templates" for a switch. The same shape the plan screen uses. */
export function describeGrant(feature: string, limit: number | null): string {
  if (limit === null) return featureLabel(feature);
  return `Up to ${limit.toLocaleString('en-US')} ${featureLabel(feature).toLowerCase()}`;
}
