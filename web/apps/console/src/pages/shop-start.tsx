import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowRight, Store } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import { useShopTypes } from '@store-app/enums';
import { shopTypeIcon } from '@store-app/icons';
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { readProblem } from '../lib/format';

/** What `GET /api/v1/shop/me` returns, as far as this screen cares. */
interface ShopMe {
  id: string;
  name: string;
  description: string | null;
  shopType: string;
  status: string;
  profileComplete: boolean;
}

/**
 * Shop sign-up (Phase 8.5) — step two of two.
 *
 * <p><b>Two steps, not one.</b> The account comes first and is created by Keycloak; the shop comes
 * second and is created here. Merging them would mean a person who abandons half the form has an
 * account they cannot use and a shop nobody can trade, and the only clean recovery is an
 * administrator.
 *
 * <p><b>The vertical is chosen here, once, and it is not editable later.</b> It constrains which
 * product categories exist, so changing it after products exist would mean reclassifying a live
 * catalogue. Choosing it at sign-up is the only point at which the choice is free.
 *
 * <p><b>This calls an endpoint that already existed.</b> `POST /api/v1/shop/signup` has been in the
 * platform since Phase 1 with nothing calling it; this is that call. It validates the name and the
 * vertical, moves the placeholder slug, and marks the profile complete — which is what stops the
 * owner being sent back here.
 */
export function ShopStart({ auth }: { auth: AuthAdapter }) {
  const { data: shopTypes } = useShopTypes();

  const [name, setName] = useState('');
  const [shopType, setShopType] = useState('');
  const [description, setDescription] = useState('');
  const [slugPreference, setSlugPreference] = useState('');

  const { data: current } = useQuery({
    queryKey: ['shop', 'me'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/me');
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as ShopMe;
    },
  });

  const signUp = useMutation({
    mutationFn: async () => {
      const response = await postJson(auth, '/api/v1/shop/signup', {
        name: name.trim(),
        shopType,
        description: description.trim() || null,
        slugPreference: slugPreference.trim() || null,
      });
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as ShopMe;
    },
    onSuccess: () => {
      // A full navigation rather than client-side routing: the console reads the shop on almost
      // every screen, and a stale cached copy would show the old name for as long as the tab lived.
      window.location.assign('/shop');
    },
  });

  // Already onboarded -- nothing to do here, and showing a form that would reset a live shop's
  // name and vertical is worse than showing nothing.
  if (current?.profileComplete) {
    return (
      <Card>
        <CardContent className="p-10 text-center">
          <Store className="mx-auto mb-3 size-8 text-muted-foreground" aria-hidden />
          <p className="font-medium">Your shop is already set up</p>
          <p className="mt-1 text-sm text-muted-foreground">
            It is called {current.name}. Change anything about it from Settings.
          </p>
          <Button asChild className="mt-4">
            <a href="/shop">Go to your dashboard</a>
          </Button>
        </CardContent>
      </Card>
    );
  }

  const ready = name.trim().length > 1 && shopType !== '';

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Tell us about your shop</h1>
        <p className="text-muted-foreground">
          Two things we need before you can start selling: what it is called, and what kind of shop
          it is. You can change the name later — the kind is the one choice you only make once.
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Your shop</CardTitle>
          <CardDescription>
            It starts as a draft. Nothing is public until you add products and an administrator
            approves it.
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-5">
          <label className="block text-sm">
            <span className="font-medium">Shop name</span>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Kantipur Pharmacy"
              maxLength={120}
              className="mt-1"
              autoFocus
            />
          </label>

          <fieldset>
            <legend className="text-sm font-medium">What kind of shop is it?</legend>
            <p className="mt-1 text-xs text-muted-foreground">
              This decides which product categories you can use. We have nineteen; the closest one
              beats a vague one.
            </p>

            <div className="mt-3 grid max-h-72 grid-cols-2 gap-2 overflow-y-auto pr-1 sm:grid-cols-3">
              {(shopTypes ?? []).map((type) => {
                const Icon = shopTypeIcon(type.icon);
                const selected = shopType === type.code;

                return (
                  <button
                    key={type.code}
                    type="button"
                    onClick={() => setShopType(type.code)}
                    aria-pressed={selected}
                    className={cn(
                      'flex items-center gap-2 rounded-lg border p-2.5 text-left text-sm transition-colors',
                      selected
                        ? 'border-primary bg-primary/5 ring-1 ring-primary'
                        : 'hover:bg-muted/50',
                    )}
                  >
                    <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    <span className="min-w-0 truncate">{type.label}</span>
                  </button>
                );
              })}
            </div>

            {shopType && !shopTypes?.some((type) => type.code === shopType) ? (
              <p className="mt-2 text-sm text-destructive">
                That is not a shop type we have. Pick one from the list.
              </p>
            ) : null}
          </fieldset>

          <label className="block text-sm">
            <span className="font-medium">Description (optional)</span>
            <textarea
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={1000}
              rows={3}
              placeholder="What do you sell, and why should somebody choose you?"
              className="mt-1 w-full resize-none rounded-md border bg-background p-2 text-sm"
            />
          </label>

          <label className="block text-sm">
            <span className="font-medium">Web address (optional)</span>
            <Input
              value={slugPreference}
              onChange={(event) => setSlugPreference(event.target.value)}
              placeholder="kantipur-pharmacy"
              maxLength={50}
              className="mt-1"
            />
            <span className="mt-1 block text-xs text-muted-foreground">
              Leave it blank and we will make one from your shop name. You can only change this
              before the shop is approved.
            </span>
          </label>

          {signUp.error ? (
            <p
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
            >
              {signUp.error.message}
            </p>
          ) : null}

          <Button onClick={() => signUp.mutate()} disabled={!ready || signUp.isPending}>
            {signUp.isPending ? 'Setting up…' : 'Create my shop'}
            {!signUp.isPending ? <ArrowRight className="ml-1.5 size-4" aria-hidden /> : null}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}