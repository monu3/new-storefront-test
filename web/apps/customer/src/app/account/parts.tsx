'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Heart, MapPin, Pencil, Plus, Trash2 } from 'lucide-react';

import { addressOneLine, type Address, type CustomerPage, type WishlistItem } from '@store-app/api-client';
import {
  Badge,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Skeleton,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { apiDelete, apiGet, apiPost, apiPut } from '../../lib/api';

/**
 * The wishlist and the saved addresses (Phase 5).
 *
 * <p>Both are small CRUD surfaces over their own endpoints, and both are here rather than in two
 * files because they share the page furniture, the optimistic update, and the honest-empty-state
 * pattern. Separating them would mean two files importing each other's pieces for the sake of a
 * route.
 */

const EMPTY_ADDRESS: AddressDraft = {
  label: '',
  recipient: '',
  phone: '',
  line1: '',
  line2: '',
  city: '',
  state: '',
  postalCode: '',
  country: 'Nepal',
  isDefault: false,
};

/** The wishlist. */
export function CustomerWishlist() {
  const [items, setItems] = useState<CustomerPage<WishlistItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setItems(await apiGet<CustomerPage<WishlistItem>>('/api/v1/customer/wishlist?size=50'));
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Could not load your wishlist');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  /** Removed from the list immediately, and restored if the server refuses. */
  const remove = async (productId: string) => {
    setPending(productId);
    setError(null);
    const previous = items;
    setItems(strip(items, productId));
    try {
      await apiDelete(`/api/v1/customer/wishlist/${productId}`);
    } catch (e) {
      setItems(previous);
      setError(e instanceof Error ? e.message : 'Could not remove that');
    } finally {
      setPending(null);
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Wishlist</h1>

      {error ? (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </p>
      ) : null}

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-56 rounded-xl" />
          ))}
        </div>
      ) : items && items.content.length > 0 ? (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {items.content.map((item) => (
            <li key={item.productId}>
              <Card className="flex h-full flex-col overflow-hidden">
                <CardContent className="flex flex-1 flex-col p-0">
                  {item.imageUrl ? (
                    <img src={item.imageUrl} alt="" className="aspect-square w-full object-cover" />
                  ) : (
                    <span
                      className="grid aspect-square w-full place-items-center bg-muted text-sm text-muted-foreground"
                      aria-hidden
                    >
                      {item.productName.slice(0, 2).toUpperCase()}
                    </span>
                  )}
                  <div className="flex flex-1 flex-col p-4">
                    <p className="line-clamp-2 text-sm font-medium">{item.productName}</p>
                    <p className="mt-1 font-semibold">{item.priceDisplay}</p>
                    <div className="mt-3 flex items-center gap-2 pt-1">
                      <Button asChild size="sm" className="flex-1">
                        {/* productSlug, not the id: the storefront link is by slug. */}
                        <Link href={`/s/products/${item.productSlug}`}>View</Link>
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`Remove ${item.productName} from your wishlist`}
                        disabled={pending === item.productId}
                        onClick={() => void remove(item.productId)}
                      >
                        <Trash2 className="size-4" aria-hidden />
                      </Button>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      ) : (
        <Card>
          <CardContent className="p-10 text-center">
            <Heart className="mx-auto size-8 text-muted-foreground" aria-hidden />
            <p className="mt-3 font-medium">Your wishlist is empty</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Tap the heart on any product to save it here.
            </p>
            <Button asChild className="mt-4">
              <Link href="/">Browse shops</Link>
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/** Saved delivery addresses. */
export function CustomerAddresses() {
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Address | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    try {
      setAddresses(await apiGet<Address[]>('/api/v1/customer/addresses'));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load your addresses');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const remove = async (id: string) => {
    setError(null);
    try {
      await apiDelete(`/api/v1/customer/addresses/${id}`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not remove that address');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Addresses</h1>
          <p className="text-sm text-muted-foreground">
            Used to prefill checkout. Orders keep their own copy, so editing these never changes
            where a past parcel went.
          </p>
        </div>
        <Button onClick={() => setEditing('new')}>
          <Plus className="mr-1.5 size-4" aria-hidden />
          Add address
        </Button>
      </div>

      {error ? (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </p>
      ) : null}

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2">
          {Array.from({ length: 2 }).map((_, i) => (
            <Skeleton key={i} className="h-32 rounded-xl" />
          ))}
        </div>
      ) : addresses.length > 0 ? (
        <ul className="grid gap-3 sm:grid-cols-2">
          {addresses.map((address) => (
            <li key={address.id}>
              <Card className={cn(address.isDefault && 'border-emerald-300')}>
                <CardContent className="p-4">
                  <div className="flex items-start justify-between gap-2">
                    <p className="font-medium">{address.label}</p>
                    {address.isDefault ? (
                      <Badge className="bg-emerald-50 text-emerald-700">Default</Badge>
                    ) : null}
                  </div>
                  <p className="mt-1 text-sm">{address.recipient}</p>
                  <p className="text-sm text-muted-foreground">{address.phone}</p>
                  <p className="mt-2 text-sm text-muted-foreground">{addressOneLine(address)}</p>
                  <div className="mt-3 flex items-center gap-1">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(address)}>
                      <Pencil className="size-4" aria-hidden />
                      Edit
                    </Button>
                    {!address.isDefault ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={async () => {
                          await apiPut(`/api/v1/customer/addresses/${address.id}`, {
                            isDefault: true,
                          });
                          await load();
                        }}
                      >
                        Make default
                      </Button>
                    ) : null}
                    <Button
                      size="sm"
                      variant="ghost"
                      aria-label={`Delete ${address.label}`}
                      onClick={() => void remove(address.id)}
                    >
                      <Trash2 className="size-4" aria-hidden />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      ) : (
        <Card>
          <CardContent className="p-10 text-center">
            <MapPin className="mx-auto size-8 text-muted-foreground" aria-hidden />
            <p className="mt-3 font-medium">No saved addresses</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Save one to fill in checkout faster next time.
            </p>
          </CardContent>
        </Card>
      )}

      {editing ? (
        <AddressDialog
          address={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load();
          }}
        />
      ) : null}
    </div>
  );
}

// -------------------------------------------------------------------- dialog

interface AddressDraft {
  label: string;
  recipient: string;
  phone: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  isDefault: boolean;
}

function AddressDialog({
  address,
  onClose,
  onSaved,
}: {
  address: Address | null;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}) {
  const [draft, setDraft] = useState<AddressDraft>(
    address
      ? {
          label: address.label,
          recipient: address.recipient,
          phone: address.phone,
          line1: address.line1,
          line2: address.line2 ?? '',
          city: address.city,
          state: address.state ?? '',
          postalCode: address.postalCode ?? '',
          country: address.country,
          isDefault: address.isDefault,
        }
      : EMPTY_ADDRESS,
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = (patch: Partial<AddressDraft>) => setDraft((c) => ({ ...c, ...patch }));

  // The five fields the backend requires. Everything else is genuinely optional.
  const missing = (['label', 'recipient', 'phone', 'line1', 'city'] as const).filter(
    (field) => !draft[field].trim(),
  );

  const save = async () => {
    if (missing.length > 0) {
      setError(`Still needed: ${missing.join(', ')}`);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const body = {
        ...draft,
        // Empty strings are sent as nulls so the server's "leave as it is" rule applies to a
        // cleared optional field rather than storing an empty string.
        line2: draft.line2 || null,
        state: draft.state || null,
        postalCode: draft.postalCode || null,
      };
      if (address) {
        await apiPut(`/api/v1/customer/addresses/${address.id}`, body);
      } else {
        await apiPost('/api/v1/customer/addresses', body);
      }
      await onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save that address');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85vh] max-w-lg overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{address ? 'Edit address' : 'Add an address'}</DialogTitle>
          <DialogDescription>
            Saved for next time. Orders keep their own copy.
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Label"
            id="a-label"
            required
            value={draft.label}
            onChange={(v) => set({ label: v })}
            placeholder="Home, Office…"
          />
          <Field
            label="Recipient"
            id="a-recipient"
            required
            value={draft.recipient}
            onChange={(v) => set({ recipient: v })}
          />
          <Field
            label="Phone"
            id="a-phone"
            required
            value={draft.phone}
            onChange={(v) => set({ phone: v })}
            placeholder="+977 98…"
          />
          <Field label="Country" id="a-country" value={draft.country} onChange={(v) => set({ country: v })} />

          <div className="sm:col-span-2">
            <Field
              label="Address line 1"
              id="a-line1"
              required
              value={draft.line1}
              onChange={(v) => set({ line1: v })}
            />
          </div>
          <div className="sm:col-span-2">
            <Field
              label="Address line 2"
              id="a-line2"
              hint="Optional"
              value={draft.line2}
              onChange={(v) => set({ line2: v })}
            />
          </div>
          <Field label="City" id="a-city" required value={draft.city} onChange={(v) => set({ city: v })} />
          <Field label="State / Province" id="a-state" value={draft.state} onChange={(v) => set({ state: v })} />
          <Field label="Postal code" id="a-postal" value={draft.postalCode} onChange={(v) => set({ postalCode: v })} />

          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="size-4"
              checked={draft.isDefault}
              onChange={(e) => set({ isDefault: e.target.checked })}
            />
            Make this my default address
          </label>

          {error ? (
            <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 sm:col-span-2">
              {error}
            </p>
          ) : null}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={() => void save()} disabled={saving}>
            {saving ? 'Saving…' : address ? 'Save changes' : 'Add address'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Field({
  label,
  id,
  value,
  onChange,
  required,
  hint,
  placeholder,
}: {
  label: string;
  id: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  hint?: string;
  placeholder?: string;
}) {
  return (
    <div>
      <Label htmlFor={id} className="flex items-center gap-1">
        {label}
        {required ? (
          <span className="text-red-500" aria-hidden>
            *
          </span>
        ) : null}
      </Label>
      <Input
        id={id}
        className="mt-1.5"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
      {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

/** Removes one item from a page of results, optimistically. */
function strip(page: CustomerPage<WishlistItem> | null, productId: string): CustomerPage<WishlistItem> | null {
  if (!page) return null;
  return { ...page, content: page.content.filter((item) => item.productId !== productId) };
}