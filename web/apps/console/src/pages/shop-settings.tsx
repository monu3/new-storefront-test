import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Bell,
  Building2,
  Check,
  CreditCard,
  Hash,
  Receipt,
  Share2,
  Store,
  Truck,
} from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type {
  PaymentMethod,
  PaymentMethodCode,
  PaymentMethodDraft,
  PaymentMethods,
  ShopSettings,
} from '@store-app/api-client';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Label,
  Separator,
  Switch,
  Textarea,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { minorToInput, readProblem, rupeesToPaisa } from '../lib/format';

/**
 * Shop settings (Phase 4).
 *
 * <p>Eight tabs, one PUT per tab, mirroring the backend's eight section endpoints. The split is
 * not cosmetic: a settings form saves a section at a time, so saving the Address tab must not be
 * able to rewrite the Social tab from a form that never loaded it.
 *
 * <p><b>Nothing here recomputes a server decision.</b> Whether a payment method can be enabled,
 * whether a discount is usable, what an amount formats as — each arrives from the API. A console
 * that recalculated them would eventually disagree with the checkout that enforced them, and the
 * owner would be the one shown two different truths.
 *
 * <p><b>Secrets are write-only.</b> The eSewa secret and the bank account number are masked
 * fields showing "set" or "not set" from a boolean. The server never sends the value, so it cannot
 * end up in a network log, a React state dump, or a screenshot of this page. Saving without typing
 * keeps the stored value; clearing it is a separate, explicit action.
 */

const TABS = [
  { key: 'general', label: 'General', icon: Store },
  { key: 'address', label: 'Address', icon: Building2 },
  { key: 'social', label: 'Social', icon: Share2 },
  { key: 'checkout', label: 'Checkout', icon: Truck },
  { key: 'notifications', label: 'Notifications', icon: Bell },
  { key: 'orderNumber', label: 'Order Numbers', icon: Hash },
  { key: 'invoice', label: 'Invoice', icon: Receipt },
  { key: 'payments', label: 'Payment Methods', icon: CreditCard },
] as const;

type TabKey = (typeof TABS)[number]['key'];

export function ShopSettingsPage({ auth }: { auth: AuthAdapter }) {
  const [tab, setTab] = useState<TabKey>('general');

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-lg font-semibold">Shop Settings</h2>
        <p className="text-sm text-muted-foreground">
          Everything about how your shop presents itself and takes orders. Each tab saves on its
          own.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[220px_minmax(0,1fr)]">
        {/* ------------------------------------------------------------- tabs */}
        <Card className="h-fit">
          <CardContent className="p-2">
            <nav aria-label="Settings sections">
              <ul className="space-y-0.5">
                {TABS.map((t) => {
                  const Icon = t.icon;
                  const active = tab === t.key;
                  return (
                    <li key={t.key}>
                      <button
                        type="button"
                        onClick={() => setTab(t.key)}
                        aria-current={active ? 'page' : undefined}
                        className={cn(
                          'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm transition-colors',
                          active
                            ? 'bg-primary font-medium text-primary-foreground'
                            : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                        )}
                      >
                        <Icon className="size-4 shrink-0" aria-hidden />
                        <span className="truncate">{t.label}</span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </nav>
          </CardContent>
        </Card>

        {/* ------------------------------------------------------------- panel */}
        <div className="space-y-4">
          {tab === 'general' ? <GeneralTab auth={auth} /> : null}
          {tab === 'address' ? <AddressTab auth={auth} /> : null}
          {tab === 'social' ? <SocialTab auth={auth} /> : null}
          {tab === 'checkout' ? <CheckoutTab auth={auth} /> : null}
          {tab === 'notifications' ? <NotificationsTab auth={auth} /> : null}
          {tab === 'orderNumber' ? <OrderNumberTab auth={auth} /> : null}
          {tab === 'invoice' ? <InvoiceTab auth={auth} /> : null}
          {tab === 'payments' ? <PaymentsTab auth={auth} /> : null}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------- shared bits

/**
 * Loads the settings document once and hands the section to a form.
 *
 * <p>Every tab reads from the same query rather than fetching its own, so switching tabs is instant
 * and the tabs cannot drift out of sync with each other.
 */
function useSettings(auth: AuthAdapter) {
  return useQuery({
    queryKey: ['shop-settings'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/settings');
      if (!response.ok) throw new Error(`Settings request failed: ${response.status}`);
      return (await response.json()) as ShopSettings;
    },
  });
}

/**
 * A tab that owns one section.
 *
 * <p>The dirty check is what decides whether Save is enabled, so the button is never clickable for
 * a change that was not made — and a tab the user typed nothing into never sends a request.
 */
function SectionForm<T extends object>({
  title,
  description,
  initial,
  endpoint,
  auth,
  children,
  preview,
}: {
  title: string;
  description?: string;
  initial: T | undefined;
  endpoint: string;
  auth: AuthAdapter;
  children: (draft: T, set: (patch: Partial<T>) => void) => React.ReactNode;
  /** A live preview of the section, so the owner sees the effect while editing. */
  preview?: (draft: T) => React.ReactNode;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<T | undefined>(initial);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Re-seed when the query resolves or refetches. Keyed on the section's identity rather than the
  // object, so a fresh object with equal contents does not discard what the owner has typed.
  //
  // `saved` is deliberately NOT reset here. Saving invalidates the query, which refetches, which
  // changes this fingerprint — so clearing it in the effect made the confirmation banner vanish
  // the instant it appeared, and a save looked like it had done nothing. `set` clears it instead,
  // which is the only thing that should: the owner editing again.
  const fingerprint = initial ? JSON.stringify(initial) : '';
  useEffect(() => {
    setDraft(initial);
    setError(null);
  }, [fingerprint]);

  const set = (patch: Partial<T>) => {
    setDraft((current) => (current ? { ...current, ...patch } : current));
    setSaved(false);
  };

  const dirty = useMemo(
    () => draft != null && initial != null && JSON.stringify(draft) !== JSON.stringify(initial),
    [draft, initial],
  );

  const save = useMutation({
    mutationFn: async () => {
      const response = await postJson(auth, endpoint, draft, 'PUT');
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => {
      setError(null);
      setSaved(true);
      void queryClient.invalidateQueries({ queryKey: ['shop-settings'] });
    },
    onError: (e: Error) => setError(e.message),
  });

  if (!draft) {
    return (
      <Card>
        <CardContent className="p-6 text-sm text-muted-foreground">Loading…</CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        {description ? <CardDescription>{description}</CardDescription> : null}
      </CardHeader>
      <CardContent className="space-y-5">
        {children(draft, set)}

        {preview ? <Separator /> : null}
        {preview ? preview(draft) : null}

        <div className="flex items-center gap-3 border-t pt-4">
          <Button
            onClick={() => save.mutate()}
            disabled={!dirty || save.isPending}
            style={{ background: '#22c55e', color: '#fff' }}
            className="border-0"
          >
            {save.isPending ? 'Saving…' : 'Save changes'}
          </Button>
          {!dirty && saved ? (
            <span className="flex items-center gap-1 text-sm text-success">
              <Check className="size-4" aria-hidden /> Saved
            </span>
          ) : null}
          {!dirty && !saved ? (
            <span className="text-sm text-muted-foreground">No changes yet</span>
          ) : null}
          {error ? (
            <span role="alert" className="text-sm text-destructive">
              {error}
            </span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

/** One labelled field, with a hint and an error that are actually announced. */
function Field({
  label,
  htmlFor,
  hint,
  children,
}: {
  label: string;
  htmlFor?: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <Label htmlFor={htmlFor}>{label}</Label>
      <div className="mt-1.5">{children}</div>
      {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

/** A switch with its explanation beside it, which is where owners actually read. */
function ToggleRow({
  id,
  label,
  description,
  checked,
  onCheckedChange,
}: {
  id: string;
  label: string;
  description: string;
  checked: boolean;
  onCheckedChange: (on: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-4 rounded-lg border p-4">
      <div className="min-w-0">
        <Label htmlFor={id} className="text-sm">
          {label}
        </Label>
        <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>
      </div>
      <Switch id={id} checked={checked} onCheckedChange={onCheckedChange} />
    </div>
  );
}

// ---------------------------------------------------------------------- tabs

function GeneralTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);
  return (
    <SectionForm
      title="General"
      description="How your shop names itself, and where customers find help."
      initial={data?.general}
      endpoint="/api/v1/shop/settings/general"
      auth={auth}
    >
      {(draft, set) => (
        <>
          <Field label="Display name" htmlFor="s-display">
            <Input
              id="s-display"
              value={draft.displayName ?? ''}
              onChange={(e) => set({ displayName: e.target.value })}
              placeholder="Leave blank to use your shop name"
            />
          </Field>
          <Field label="Tagline" htmlFor="s-tagline" hint="One line, shown under your shop name.">
            <Input
              id="s-tagline"
              value={draft.tagline ?? ''}
              onChange={(e) => set({ tagline: e.target.value })}
            />
          </Field>
          <Field label="About" htmlFor="s-about">
            <Textarea
              id="s-about"
              rows={4}
              value={draft.about ?? ''}
              onChange={(e) => set({ about: e.target.value })}
            />
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Support email" htmlFor="s-email">
              <Input
                id="s-email"
                type="email"
                value={draft.supportEmail ?? ''}
                onChange={(e) => set({ supportEmail: e.target.value })}
                placeholder="help@example.com"
              />
            </Field>
            <Field label="Support phone" htmlFor="s-phone">
              <Input
                id="s-phone"
                value={draft.supportPhone ?? ''}
                onChange={(e) => set({ supportPhone: e.target.value })}
                placeholder="+977 9800000000"
              />
            </Field>
          </div>
        </>
      )}
    </SectionForm>
  );
}

function AddressTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);
  return (
    <SectionForm
      title="Address"
      description="Printed on every invoice, and used as your delivery origin."
      initial={data?.address}
      endpoint="/api/v1/shop/settings/address"
      auth={auth}
      preview={(draft) => {
        const line = [draft.line1, draft.line2, draft.city, draft.state, draft.postalCode, draft.country]
          .filter(Boolean)
          .join(', ');
        return (
          <div className="rounded-lg bg-muted/50 p-3">
            <p className="text-xs font-medium text-muted-foreground">On your invoice</p>
            <p className="mt-1 text-sm">{line || 'No address set yet.'}</p>
          </div>
        );
      }}
    >
      {(draft, set) => (
        <>
          <Field label="Address line 1" htmlFor="a-line1">
            <Input
              id="a-line1"
              value={draft.line1 ?? ''}
              onChange={(e) => set({ line1: e.target.value })}
            />
          </Field>
          <Field label="Address line 2" htmlFor="a-line2">
            <Input
              id="a-line2"
              value={draft.line2 ?? ''}
              onChange={(e) => set({ line2: e.target.value })}
            />
          </Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="City" htmlFor="a-city">
              <Input
                id="a-city"
                value={draft.city ?? ''}
                onChange={(e) => set({ city: e.target.value })}
              />
            </Field>
            <Field label="State / Province" htmlFor="a-state">
              <Input
                id="a-state"
                value={draft.state ?? ''}
                onChange={(e) => set({ state: e.target.value })}
              />
            </Field>
            <Field label="Postal code" htmlFor="a-postal">
              <Input
                id="a-postal"
                value={draft.postalCode ?? ''}
                onChange={(e) => set({ postalCode: e.target.value })}
              />
            </Field>
          </div>
          <Field label="Country" htmlFor="a-country">
            <Input
              id="a-country"
              value={draft.country ?? ''}
              onChange={(e) => set({ country: e.target.value })}
              placeholder="Nepal"
            />
          </Field>
          <Field
            label="Delivery instructions"
            htmlFor="a-delivery"
            hint="Shown to your delivery partner. Free text — no machine routing depends on it."
          >
            <Textarea
              id="a-delivery"
              rows={3}
              value={draft.deliveryInstructions ?? ''}
              onChange={(e) => set({ deliveryInstructions: e.target.value })}
            />
          </Field>
        </>
      )}
    </SectionForm>
  );
}

function SocialTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);
  return (
    <SectionForm
      title="Social"
      description="Links shown in your storefront footer. Blank fields are not shown at all."
      initial={data?.social}
      endpoint="/api/v1/shop/settings/social"
      auth={auth}
      preview={(draft) => {
        const filled = Object.values(draft).filter((v) => v && String(v).trim());
        return (
          <div className="rounded-lg bg-muted/50 p-3">
            <p className="text-xs font-medium text-muted-foreground">
              {filled.length} link{filled.length === 1 ? '' : 's'} in your footer
            </p>
          </div>
        );
      }}
    >
      {(draft, set) => (
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Website" htmlFor="so-web">
            <Input
              id="so-web"
              value={draft.websiteUrl ?? ''}
              onChange={(e) => set({ websiteUrl: e.target.value })}
              placeholder="https://…"
            />
          </Field>
          <Field label="Facebook" htmlFor="so-fb">
            <Input
              id="so-fb"
              value={draft.facebookUrl ?? ''}
              onChange={(e) => set({ facebookUrl: e.target.value })}
              placeholder="https://facebook.com/…"
            />
          </Field>
          <Field label="Instagram" htmlFor="so-ig">
            <Input
              id="so-ig"
              value={draft.instagramUrl ?? ''}
              onChange={(e) => set({ instagramUrl: e.target.value })}
            />
          </Field>
          <Field label="WhatsApp" htmlFor="so-wa">
            <Input
              id="so-wa"
              value={draft.whatsappUrl ?? ''}
              onChange={(e) => set({ whatsappUrl: e.target.value })}
              placeholder="https://wa.me/…"
            />
          </Field>
          <Field label="YouTube" htmlFor="so-yt">
            <Input
              id="so-yt"
              value={draft.youtubeUrl ?? ''}
              onChange={(e) => set({ youtubeUrl: e.target.value })}
            />
          </Field>
          <Field label="TikTok" htmlFor="so-tt">
            <Input
              id="so-tt"
              value={draft.tiktokUrl ?? ''}
              onChange={(e) => set({ tiktokUrl: e.target.value })}
            />
          </Field>
        </div>
      )}
    </SectionForm>
  );
}

function CheckoutTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);
  return (
    <SectionForm
      title="Checkout"
      description="Whether you are taking orders, and what a customer sees when they do."
      initial={data?.checkout}
      endpoint="/api/v1/shop/settings/checkout"
      auth={auth}
    >
      {(draft, set) => (
        <>
          <ToggleRow
            id="c-accept"
            label="Accepting orders"
            description={
              draft.acceptOrders
                ? 'Your storefront is open and baskets can be placed.'
                : 'Your storefront shows as closed and checkout is refused. Use this overnight rather than deleting products.'
            }
            checked={draft.acceptOrders}
            onCheckedChange={(v) => set({ acceptOrders: v })}
          />
          <ToggleRow
            id="c-auto"
            label="Auto-accept orders"
            description="Accept orders as soon as stock allows, without you pressing Accept."
            checked={draft.autoAcceptOrders}
            onCheckedChange={(v) => set({ autoAcceptOrders: v })}
          />
          <ToggleRow
            id="c-estimate"
            label="Show delivery estimate"
            description="Display an estimated delivery window on product pages."
            checked={draft.showDeliveryEstimate}
            onCheckedChange={(v) => set({ showDeliveryEstimate: v })}
          />
          <ToggleRow
            id="c-guest"
            label="Allow guest checkout"
            description="Customers can order without creating an account."
            checked={draft.guestCheckoutAllowed}
            onCheckedChange={(v) => set({ guestCheckoutAllowed: v })}
          />
          <Field
            label="Minimum order (NPR)"
            htmlFor="c-min"
            hint="Leave at 0 for no minimum. A customer below it is told the amount still needed."
          >
            <Input
              id="c-min"
              type="number"
              min="0"
              step="0.01"
              value={minorToInput(draft.minOrderMinor ?? 0)}
              onChange={(e) => set({ minOrderMinor: rupeesToPaisa(e.target.value) })}
            />
          </Field>
          <Field label="Note shown at checkout" htmlFor="c-note">
            <Textarea
              id="c-note"
              rows={3}
              value={draft.checkoutNote ?? ''}
              onChange={(e) => set({ checkoutNote: e.target.value })}
              placeholder="e.g. Delivery within 24 hours inside Kathmandu valley."
            />
          </Field>
        </>
      )}
    </SectionForm>
  );
}

function NotificationsTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);
  return (
    <SectionForm
      title="Notifications"
      description="What you get told about, and what counts as low stock."
      initial={data?.notifications}
      endpoint="/api/v1/shop/settings/notifications"
      auth={auth}
    >
      {(draft, set) => (
        <>
          <ToggleRow
            id="n-new"
            label="New orders"
            description="Told as soon as a customer places one."
            checked={draft.newOrder}
            onCheckedChange={(v) => set({ newOrder: v })}
          />
          <ToggleRow
            id="n-low"
            label="Low stock"
            description="Told when accepting an order takes a product below its threshold. Not sent repeatedly while it stays low."
            checked={draft.lowStock}
            onCheckedChange={(v) => set({ lowStock: v })}
          />
          <ToggleRow
            id="n-digest"
            label="Email digest"
            description="Send these by email as well as showing them in your console."
            checked={draft.emailDigest}
            onCheckedChange={(v) => set({ emailDigest: v })}
          />
          <Field
            label="Low-stock warning level"
            htmlFor="n-threshold"
            hint="The fallback for products without their own threshold. Each variant can override it."
          >
            <Input
              id="n-threshold"
              type="number"
              min="0"
              step="1"
              value={draft.lowStockThreshold ?? 5}
              onChange={(e) => set({ lowStockThreshold: Number(e.target.value) })}
            />
          </Field>
        </>
      )}
    </SectionForm>
  );
}

const FORMAT_TOKENS = [
  { token: '{YYYY}', label: 'Four-digit year, e.g. 2026' },
  { token: '{YY}', label: 'Two-digit year, e.g. 26' },
  { token: '{MM}', label: 'Two-digit month, e.g. 03' },
  { token: '{DD}', label: 'Two-digit day, e.g. 04' },
  { token: '{####}', label: 'A counter, zero-padded to the width you type' },
];

function OrderNumberTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);

  return (
    <SectionForm
      title="Order Numbers"
      description="How your order numbers are written."
      initial={data?.orderNumber}
      endpoint="/api/v1/shop/settings/order-number"
      auth={auth}
      preview={(draft) => (
        <div className="rounded-lg bg-muted/50 p-3">
          <p className="text-xs font-medium text-muted-foreground">
            Next order will be numbered
          </p>
          <p className="mt-1 font-mono text-sm">
            {previewFormat(draft.format ?? 'ORD-{####}', draft.next ?? 1)}
          </p>
          <p className="mt-2 text-xs text-muted-foreground">
            Changing the format does not renumber existing orders. Past invoices keep the number
            they were issued with.
          </p>
        </div>
      )}
    >
      {(draft, set) => (
        <>
          <Field
            label="Format"
            htmlFor="on-format"
            hint="Use the tokens below. At least one counter token is required."
          >
            <Input
              id="on-format"
              value={draft.format ?? ''}
              onChange={(e) => set({ format: e.target.value })}
              placeholder="ORD-{####}"
              className="font-mono"
            />
          </Field>
          <ul className="grid gap-1 rounded-lg border bg-muted/30 p-3 text-xs sm:grid-cols-2">
            {FORMAT_TOKENS.map((t) => (
              <li key={t.token} className="flex items-center gap-2">
                <code className="rounded bg-background px-1.5 py-0.5 font-mono">{t.token}</code>
                <span className="text-muted-foreground">{t.label}</span>
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted-foreground">
            The counter itself is advanced inside the checkout transaction and cannot be edited
            here. It is currently at {draft.next ?? 1}.
          </p>
        </>
      )}
    </SectionForm>
  );
}

/**
 * Renders a format string client-side, for the preview only.
 *
 * <p>Deliberately a separate, simpler implementation from the server's renderer. The server's
 * version is the authority — it numbers real orders — and a preview must never be mistaken for it.
 * If the two ever disagree, the server is right and this is a preview bug.
 */
function previewFormat(format: string, counter: number): string {
  const today = new Date();
  const pad = (n: number, w = 2) => String(n).padStart(w, '0');
  return format
    .replace('{YYYY}', pad(today.getFullYear(), 4))
    .replace('{YY}', pad(today.getFullYear() % 100))
    .replace('{MM}', pad(today.getMonth() + 1))
    .replace('{DD}', pad(today.getDate()))
    .replace(/\{#+\}/, pad(counter, (format.match(/\{#+/) ?? ['{####}'])[0].length - 2));
}

function InvoiceTab({ auth }: { auth: AuthAdapter }) {
  const { data } = useSettings(auth);
  return (
    <SectionForm
      title="Invoice"
      description="How the PDF invoice looks. The numbers on it are always taken from the order itself."
      initial={data?.invoiceTemplate}
      endpoint="/api/v1/shop/settings/invoice"
      auth={auth}
    >
      {(draft, set) => (
        <>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Accent colour" htmlFor="i-accent" hint="A six-digit hex colour, e.g. #1f2937">
              <div className="flex items-center gap-2">
                <Input
                  id="i-accent"
                  value={draft.accentColor ?? ''}
                  onChange={(e) => set({ accentColor: e.target.value })}
                  placeholder="#1f2937"
                  className="font-mono"
                />
                <span
                  aria-hidden
                  className="size-8 shrink-0 rounded border"
                  style={{ background: draft.accentColor || '#cccccc' }}
                />
              </div>
            </Field>
            <Field label="Business / Tax ID" htmlFor="i-tax">
              <Input
                id="i-tax"
                value={draft.businessTaxId ?? ''}
                onChange={(e) => set({ businessTaxId: e.target.value })}
              />
            </Field>
          </div>
          <ToggleRow
            id="i-logo"
            label="Show logo"
            description="Print your shop logo at the top of the invoice."
            checked={draft.showLogo}
            onCheckedChange={(v) => set({ showLogo: v })}
          />
          <ToggleRow
            id="i-sig"
            label="Signature line"
            description="A ruled space at the bottom for a hand signature."
            checked={draft.showSignatureLine}
            onCheckedChange={(v) => set({ showSignatureLine: v })}
          />
          <ToggleRow
            id="i-taxbreak"
            label="Show tax breakdown"
            description="Print tax as its own line rather than folding it into the total."
            checked={draft.showTaxBreakdown}
            onCheckedChange={(v) => set({ showTaxBreakdown: v })}
          />
          <ToggleRow
            id="i-pay"
            label="Show payment method"
            description="Print how the customer is paying."
            checked={draft.showPaymentMethod}
            onCheckedChange={(v) => set({ showPaymentMethod: v })}
          />
          <Field label="Footer note" htmlFor="i-footer">
            <Textarea
              id="i-footer"
              rows={2}
              value={draft.footerNote ?? ''}
              onChange={(e) => set({ footerNote: e.target.value })}
              placeholder="Thank you for your order."
            />
          </Field>
        </>
      )}
    </SectionForm>
  );
}

// ---------------------------------------------------------------------- payments

function PaymentsTab({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [clearing, setClearing] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['shop-payments'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/payments');
      if (!response.ok) throw new Error(`Payment methods request failed: ${response.status}`);
      return (await response.json()) as PaymentMethods;
    },
  });

  if (isLoading || !data) {
    return (
      <Card>
        <CardContent className="p-6 text-sm text-muted-foreground">Loading…</CardContent>
      </Card>
    );
  }

  const clearSecret = async (method: PaymentMethodCode) => {
    const response = await authorizedFetch(
      auth,
      `/api/v1/shop/payments/${method}/secret`,
      { method: 'DELETE' },
    );
    if (response.ok) {
      setClearing(null);
      void queryClient.invalidateQueries({ queryKey: ['shop-payments'] });
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Payment Methods</CardTitle>
          <CardDescription>
            What your customers can pay with. A method is only offered once it has everything it
            needs to take the payment.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!data.secretsAvailable ? (
            <div
              role="status"
              className="rounded-lg border border-amber-200 bg-warning/10 p-3 text-sm text-foreground"
            >
              <p className="font-medium">Secrets cannot be stored on this server.</p>
              <p className="mt-1">
                Merchant secrets and bank account numbers are disabled. Set{' '}
                <code className="font-mono">STORE_SECRETS_KEY</code> and restart to enable them.
                Cash on delivery works without it.
              </p>
            </div>
          ) : null}
        </CardContent>
      </Card>

      {data.methods.map((method) => (
        <PaymentMethodCard
          key={method.method}
          auth={auth}
          method={method}
          secretsAvailable={data.secretsAvailable}
          clearing={clearing === method.method}
          onRequestClear={() => setClearing(method.method)}
          onClear={() => clearSecret(method.method)}
          onCancelClear={() => setClearing(null)}
        />
      ))}
    </div>
  );
}

function PaymentMethodCard({
  auth,
  method,
  secretsAvailable,
  clearing,
  onRequestClear,
  onClear,
  onCancelClear,
}: {
  auth: AuthAdapter;
  method: PaymentMethod;
  secretsAvailable: boolean;
  clearing: boolean;
  onRequestClear: () => void;
  onClear: () => void;
  onCancelClear: () => void;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<PaymentMethodDraft>(() => toDraft(method));
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // Re-seed when the server's view changes, including after a secret is saved or cleared.
  const fingerprint = JSON.stringify(method);
  useEffect(() => {
    setDraft(toDraft(method));
    setSaved(false);
  }, [fingerprint]);

  const set = (patch: Partial<PaymentMethodDraft>) => {
    setDraft((c) => ({ ...c, ...patch }));
    setSaved(false);
  };

  const dirty = JSON.stringify(draft) !== JSON.stringify(toDraft(method));

  const save = useMutation({
    mutationFn: async () => {
      const response = await postJson(
        auth,
        `/api/v1/shop/payments/${method.method}`,
        draft,
        'PUT',
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => {
      setError(null);
      setSaved(true);
      void queryClient.invalidateQueries({ queryKey: ['shop-payments'] });
    },
    onError: (e: Error) => setError(e.message),
  });

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <CardTitle className="flex items-center gap-2">
            {method.customerLabel}
            {method.enabled ? (
              <Badge className="bg-success/10 text-success">On</Badge>
            ) : (
              <Badge variant="secondary">Off</Badge>
            )}
          </CardTitle>
          <Switch
            id={`pm-enabled-${method.method}`}
            checked={draft.enabled}
            aria-label={`Enable ${method.customerLabel}`}
            onCheckedChange={(v) => set({ enabled: v })}
          />
        </div>
        <CardDescription>
          {method.enabled
            ? 'Offered to customers on their basket.'
            : (method.missingConfiguration
                ? `Not offered yet: ${method.missingConfiguration}.`
                : 'Not offered to customers.')}
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        <Field label="Label customers see" htmlFor={`pm-label-${method.method}`}>
          <Input
            id={`pm-label-${method.method}`}
            value={draft.displayLabel ?? ''}
            onChange={(e) => set({ displayLabel: e.target.value })}
            placeholder={method.customerLabel}
          />
        </Field>

        {method.method === 'ESAWA' ? (
          <>
            <Field label="Merchant ID" htmlFor="pm-merchant">
              <Input
                id="pm-merchant"
                value={draft.esewaMerchantId ?? ''}
                onChange={(e) => set({ esewaMerchantId: e.target.value })}
                placeholder="EPAY1234"
              />
            </Field>
            <SecretField
              id="pm-secret"
              label="Merchant secret"
              isSet={method.hasEsewaSecret}
              disabled={!secretsAvailable}
              value={draft.esewaSecret ?? ''}
              onChange={(v) => set({ esewaSecret: v })}
              onRequestClear={onRequestClear}
              clearing={clearing}
              onClear={onClear}
              onCancelClear={onCancelClear}
            />
          </>
        ) : null}

        {method.method === 'BANK_TRANSFER' ? (
          <>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Bank name" htmlFor="pm-bank">
                <Input
                  id="pm-bank"
                  value={draft.bankName ?? ''}
                  onChange={(e) => set({ bankName: e.target.value })}
                />
              </Field>
              <Field label="Account name" htmlFor="pm-acctname">
                <Input
                  id="pm-acctname"
                  value={draft.bankAccountName ?? ''}
                  onChange={(e) => set({ bankAccountName: e.target.value })}
                />
              </Field>
            </div>
            <SecretField
              id="pm-acctnum"
              label="Account number"
              isSet={method.hasBankAccountNumber}
              disabled={!secretsAvailable}
              value={draft.bankAccountNumber ?? ''}
              onChange={(v) => set({ bankAccountNumber: v })}
              onRequestClear={onRequestClear}
              clearing={clearing}
              onClear={onClear}
              onCancelClear={onCancelClear}
            />
          </>
        ) : null}

        {method.method === 'COD' ? (
          <Field
            label="Cash-on-delivery fee (NPR)"
            htmlFor="pm-codfee"
            hint="Added to every order paid in cash. Usually covers the rider's time."
          >
            <Input
              id="pm-codfee"
              type="number"
              min="0"
              step="0.01"
              value={minorToInput(draft.codFeeMinor)}
              onChange={(e) => set({ codFeeMinor: rupeesToPaisa(e.target.value) })}
            />
          </Field>
        ) : null}

        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Minimum order (NPR)" htmlFor="pm-min">
            <Input
              id="pm-min"
              type="number"
              min="0"
              step="0.01"
              value={minorToInput(draft.minimumMinor)}
              onChange={(e) => set({ minimumMinor: rupeesToPaisa(e.target.value) })}
            />
          </Field>
          <Field label="Maximum order (NPR)" htmlFor="pm-max" hint="Leave blank for no limit.">
            <Input
              id="pm-max"
              type="number"
              min="0"
              step="0.01"
              value={draft.maxOrderMinor == null ? '' : minorToInput(draft.maxOrderMinor)}
              onChange={(e) =>
                set({ maxOrderMinor: e.target.value === '' ? null : rupeesToPaisa(e.target.value) })
              }
            />
          </Field>
          <Field label="Position" htmlFor="pm-sort" hint="Lower appears first.">
            <Input
              id="pm-sort"
              type="number"
              min="0"
              step="1"
              value={draft.sortOrder}
              onChange={(e) => set({ sortOrder: Number(e.target.value) })}
            />
          </Field>
        </div>

        <Field
          label="Instructions"
          htmlFor="pm-instructions"
          hint="Shown under this method at checkout, e.g. where to send the transfer."
        >
          <Textarea
            id="pm-instructions"
            rows={2}
            value={draft.instructions ?? ''}
            onChange={(e) => set({ instructions: e.target.value })}
          />
        </Field>

        <div className="flex flex-wrap items-center gap-3 border-t pt-4">
          <Button
            onClick={() => save.mutate()}
            disabled={!dirty || save.isPending}
            style={{ background: '#22c55e', color: '#fff' }}
            className="border-0"
          >
            {save.isPending ? 'Saving…' : 'Save changes'}
          </Button>
          {saved && !dirty ? (
            <span className="flex items-center gap-1 text-sm text-success">
              <Check className="size-4" aria-hidden /> Saved
            </span>
          ) : null}
          {error ? (
            <span role="alert" className="text-sm text-destructive">
              {error}
            </span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * A masked secret field.
 *
 * <p>Shows the *state* ("stored", "not stored") rather than the value, because the server never
 * sends it. Saving with the field blank keeps whatever is stored — the alternative is a save that
 * silently deletes a working merchant credential. Forgetting it is a two-step confirmation, because
 * it cannot be undone.
 */
function SecretField({
  id,
  label,
  isSet,
  disabled,
  value,
  onChange,
  onRequestClear,
  clearing,
  onClear,
  onCancelClear,
}: {
  id: string;
  label: string;
  isSet: boolean;
  disabled: boolean;
  value: string;
  onChange: (value: string) => void;
  onRequestClear: () => void;
  clearing: boolean;
  onClear: () => void;
  onCancelClear: () => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <div className="mt-1.5 space-y-2">
        <Input
          id={id}
          type="password"
          autoComplete="new-password"
          disabled={disabled}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={isSet ? 'Stored — type to replace' : 'Not set yet'}
        />
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={isSet ? 'secondary' : 'outline'}>
            {isSet ? 'Stored' : 'Not stored'}
          </Badge>
          {isSet && !clearing ? (
            <button
              type="button"
              onClick={onRequestClear}
              className="text-xs text-destructive underline underline-offset-2"
            >
              Forget it
            </button>
          ) : null}
          {clearing ? (
            <>
              <span className="text-xs text-muted-foreground">
                This cannot be undone. The method stops being offered.
              </span>
              <Button size="sm" variant="destructive" onClick={onClear}>
                Yes, forget it
              </Button>
              <Button size="sm" variant="ghost" onClick={onCancelClear}>
                Keep it
              </Button>
            </>
          ) : null}
        </div>
        {disabled ? (
          <p className="text-xs text-warning">
            Disabled: this server is not configured to store secrets.
          </p>
        ) : null}
      </div>
    </div>
  );
}

function toDraft(m: PaymentMethod): PaymentMethodDraft {
  return {
    esewaMerchantId: m.esewaMerchantId,
    // Always blank: the stored value is never held in component state, only re-typed.
    esewaSecret: '',
    bankName: m.bankName,
    bankAccountName: m.bankAccountName,
    bankAccountNumber: '',
    codFeeMinor: m.codFeeMinor,
    minimumMinor: m.minimumMinor,
    maxOrderMinor: m.maxOrderMinor,
    displayLabel: m.displayLabel,
    instructions: m.instructions,
    sortOrder: m.sortOrder,
    enabled: m.enabled,
  };
}