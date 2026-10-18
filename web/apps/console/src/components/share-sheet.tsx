import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Check, Copy, Facebook, Link2, MessageCircle, Send } from 'lucide-react';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Skeleton,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { readProblem } from '../lib/format';

/**
 * One product's share sheet, as the API returns it.
 *
 * <p>Every channel is a ready-made URL. The front end never assembles one, because URL encoding a
 * product name in the browser is exactly the place a `&` in "Tea & Coffee" turns into half a link
 * that still pastes and still goes somewhere wrong.
 */
interface ProductShare {
  productId: string;
  productName: string;
  slug: string;
  shopId: string;
  shopName: string;
  url: string;
  whatsapp: string;
  messenger: string;
  viber: string;
  copyText: string;
}

interface ShopMe {
  id: string;
  name: string;
  slug: string;
}

/**
 * The share sheet (Phase 8 — the multi-channel requirement).
 *
 * <p><b>WhatsApp is the primary button, not one of four.</b> It is the channel most customers in this
 * market actually use, and a share sheet where the thing somebody wants is one of four equal buttons
 * is a share sheet that has not decided what it is for.
 *
 * <p><b>Copy is present and says so when it worked.</b> `navigator.clipboard` is unavailable on an
 * insecure origin, and a copy button that silently does nothing is worse than no button — so the
 * failure path shows the text and tells the reader to select it.
 *
 * <p><b>Every link is attributed.</b> A share feature without attribution is decoration: it works,
 * nobody can tell, and nobody can tell whether to invest in it.
 */
export function ShareSheet({
  auth,
  productId,
  productName,
  onClose,
}: {
  auth: AuthAdapter;
  productId: string;
  productName: string;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);

  const { data: shop } = useQuery({
    queryKey: ['shop', 'me'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/me');
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as ShopMe;
    },
  });

  const { data, isLoading, error } = useQuery({
    queryKey: ['share', 'product', productId],
    queryFn: async () => {
      const response = await authorizedFetch(
        auth,
        `/api/v1/shops/${shop!.id}/products/${productId}/share`,
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as ProductShare;
    },
    enabled: Boolean(shop?.id),
  });

  /**
   * Copies the share text.
   *
   * <p>Falls back to showing the text rather than doing nothing. `navigator.clipboard` is only
   * available on a secure origin, and localhost counts as secure while an IP address does not — so
   * this silently no-ops for anyone running the console somewhere else, and a button that quietly
   * fails is worse than one that admits it.
   */
  const copy = useMutation({
    mutationFn: async () => {
      if (!navigator.clipboard) {
        throw new Error('This browser will not let a page copy to the clipboard here.');
      }
      await navigator.clipboard.writeText(data!.copyText);
    },
    onSuccess: () => {
      setCopied(true);
      setCopyFailed(false);
      // Reset rather than latching: a share sheet left open would otherwise claim "copied" for a
      // link the reader copied five minutes ago and has since changed.
      window.setTimeout(() => setCopied(false), 2500);
    },
    onError: () => setCopyFailed(true),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Share {productName}</CardTitle>
        <CardDescription>
          Every link carries a tag, so you can see afterwards which channel actually brought people
          in.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        {isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : error || !data ? (
          <p role="alert" className="text-sm text-destructive">
            {error?.message ?? 'Could not build the share links.'}
          </p>
        ) : (
          <>
            {/* WhatsApp first and largest: it is the channel this market actually uses. */}
            <div className="flex flex-wrap gap-2">
              <Button asChild className="bg-[#25D366] hover:bg-[#1eb955]">
                <a href={data.whatsapp} target="_blank" rel="noreferrer noopener">
                  <MessageCircle className="mr-1.5 size-4" aria-hidden />
                  Share on WhatsApp
                </a>
              </Button>

              <Button asChild variant="outline">
                <a href={data.messenger} target="_blank" rel="noreferrer noopener">
                  <Send className="mr-1.5 size-4" aria-hidden />
                  Messenger
                </a>
              </Button>

              <Button asChild variant="outline">
                <a href={data.viber} target="_blank" rel="noreferrer noopener">
                  <MessageCircle className="mr-1.5 size-4" aria-hidden />
                  Viber
                </a>
              </Button>

              <Button
                asChild
                variant="outline"
                className={cn(copy.isPending && 'opacity-70')}
              >
                <a href={data.url} target="_blank" rel="noreferrer noopener">
                  <Facebook className="mr-1.5 size-4" aria-hidden />
                  Open link
                </a>
              </Button>
            </div>

            {/* Copy, with the text always visible so a failed clipboard is recoverable. */}
            <div className="space-y-2">
              <label className="text-sm font-medium" htmlFor={`copy-${productId}`}>
                Or copy this
              </label>
              <textarea
                id={`copy-${productId}`}
                readOnly
                value={data.copyText}
                rows={2}
                className="w-full resize-none rounded-md border bg-muted/30 p-2 text-sm"
              />
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => copy.mutate()}
                  disabled={copy.isPending}
                  data-testid="share-copy"
                >
                  {copied ? (
                    <>
                      <Check className="mr-1.5 size-4" aria-hidden />
                      Copied
                    </>
                  ) : (
                    <>
                      <Copy className="mr-1.5 size-4" aria-hidden />
                      Copy text
                    </>
                  )}
                </Button>
                {copyFailed ? (
                  <span className="text-xs text-muted-foreground" role="status">
                    This browser will not let the page copy for you — select the text above and copy
                    it yourself.
                  </span>
                ) : null}
              </div>
            </div>

            <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
              <Link2 className="mt-0.5 size-3.5 shrink-0" aria-hidden />
              <span>{data.url}</span>
            </p>

            <Button variant="ghost" size="sm" onClick={onClose}>
              Close
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  );
}