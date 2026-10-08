import { Link } from 'react-router-dom';
import { ArrowRight, Sparkles } from 'lucide-react';

import type { EntitlementDenial } from '@store-app/api-client';
import { Button, Card, CardContent } from '@store-app/ui';

import { describeGrant, featureLabel } from '../lib/entitlements';

/**
 * The upgrade prompt shown when a shop hits a plan limit (Phase 6).
 *
 * <p><b>This exists because a bare 403 is a wall.</b> A shop owner told "HTTP 403" learns nothing
 * and concludes the product is broken. The server already sends what would make it a yes — the
 * feature, the numbers, and the plan that unlocks it — so this renders that as a purchase, or says
 * plainly that no plan on sale grants it.
 *
 * <p><b>Null {@code upgradeTo} renders no button.</b> "No plan currently offers it" is a different
 * answer from "upgrade", and sending someone to a pricing page where nothing can be bought is worse
 * than sending them nowhere.
 *
 * <p>Used wherever a limit is enforced. One component rather than three copies, because the three
 * would drift — and the drift would show up as one screen offering a plan that does not grant the
 * feature.
 */
export function UpgradePrompt({ denial }: { denial: EntitlementDenial }) {
  return (
    <Card className="border-amber-300 bg-amber-50/50">
      <CardContent className="space-y-3 p-4">
        <div className="flex items-start gap-2">
          <Sparkles className="mt-0.5 size-4 shrink-0 text-amber-700" aria-hidden />
          <div className="min-w-0">
            <p className="font-medium text-amber-900">
              {featureLabel(denial.feature)} is not in your plan
            </p>
            <p className="mt-0.5 text-sm text-amber-900">{denial.message}</p>
          </div>
        </div>

        {denial.limit !== null ? (
          <p className="text-sm text-amber-900">
            You have {denial.currentCount?.toLocaleString('en-US') ?? '—'} of{' '}
            {denial.limit.toLocaleString('en-US')} {describeGrant(denial.feature, null).toLowerCase()}
            .
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          {denial.upgradeTo ? (
            <Button asChild size="sm">
              <Link to="/shop/billing">
                See plans
                <ArrowRight className="ml-1.5 size-3.5" aria-hidden />
              </Link>
            </Button>
          ) : (
            <p className="text-sm text-amber-800">
              No plan currently offers this. Removing products you no longer sell will free up space.
            </p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
