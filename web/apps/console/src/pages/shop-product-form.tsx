import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { Boxes, CircleDollarSign, ImageUp, Package, Plus, Trash2 } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Switch,
  Textarea,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import type { EntitlementDenial } from '@store-app/api-client';

import { UpgradePrompt } from '../components/upgrade-prompt';
import { readEntitlementDenial } from '../lib/entitlements';

/**
 * Create or edit a product (Phase 4).
 *
 * <p>Replicated from the reference: a section stepper on the left, four form cards on the right,
 * and a sticky Discard / Add product bar.
 *
 * <p><b>The category list comes from the shop's own type</b> (D17). A grocery shop is offered its
 * grocery categories and nothing else, so an invalid pair cannot be typed into the form — the
 * server would reject it, but making an owner discover that by saving is pointless.
 *
 * <p><b>Price and discount are entered in rupees and stored in paisa.</b> The conversion happens
 * once, here, because the whole system is paisa-integer end to end and a float crossing that
 * boundary is how rounding bugs enter a ledger.
 */

interface CategoryOption {
  code: string;
  label: string;
}

/** The server's `CategoryOptions`: what this shop's type allows, and what it already uses. */
interface ShopMeta {
  shopType: string;
  allowed: CategoryOption[];
  inUse: string[];
}

interface VariantDraft {
  name: string;
  price: string;
  sku: string;
  stock: string;
}

const STEPS = [
  { key: 'info', label: 'Product Information', hint: 'Basic product details', icon: Package },
  { key: 'media', label: 'Product Media', hint: 'Upload product images', icon: ImageUp },
  { key: 'inventory', label: 'Inventory', hint: 'Stock and pricing', icon: Boxes },
  { key: 'variants', label: 'Variants', hint: 'Product variations', icon: CircleDollarSign },
] as const;

export function ShopProductForm({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();
  const { productId } = useParams<{ productId: string }>();
  const editing = Boolean(productId);

  const [step, setStep] = useState<string>('info');
  const [name, setName] = useState('');
  const [category, setCategory] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('0.00');
  const [discountPct, setDiscountPct] = useState('0');
  const [stock, setStock] = useState('');
  const [useVariants, setUseVariants] = useState(false);
  const [variants, setVariants] = useState<VariantDraft[]>([]);
  const [problems, setProblems] = useState<Problem[]>([]);
  const [images, setImages] = useState<ProductImage[]>([]);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // A refused plan limit, kept apart from `problems`: it is not a field error, and putting
  // "upgrade to Pro" under the product-name field is how a shop concludes it mistyped.
  const [denial, setDenial] = useState<EntitlementDenial | null>(null);

  const { data: meta } = useQuery({
    queryKey: ['shop-meta'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/products/categories');
      if (!response.ok) throw new Error(`Categories request failed: ${response.status}`);
      return (await response.json()) as ShopMeta;
    },
  });

  const compareAt = useMemo(() => {
    const gross = toPaisa(price);
    const pct = Number(discountPct);
    if (!gross || !Number.isFinite(pct) || pct <= 0) return null;
    const discounted = Math.round(gross * (1 - pct / 100));
    return discounted > 0 ? gross : null;
  }, [price, discountPct]);

  async function submit() {
    const found = validate({ name, category, price, description, stock, useVariants, variants });
    setProblems(found);
    // `noUncheckedIndexedAccess` cannot see that `length > 0` implies `[0]` exists, and the first
    // problem is the one the owner most needs to see, so it is named rather than indexed.
    const [first] = found;
    if (first) {
      // Jump to the first step that is actually wrong, so the owner is not left hunting. Keyed off
      // the problem's own step, never off its prose.
      setStep(first.step);
      return;
    }

    setSaving(true);
    try {
      // The reference collects one price for the whole product; this platform prices a variant,
      // so the default variant carries it. That is also why Variants is not optional for
      // publishing: a product with no priced variant cannot go live.
      const body = {
        name: name.trim(),
        category,
        shortDescription: description.trim().slice(0, 300) || null,
        description: description.trim() || null,
        compareAtMinor: compareAt,
      };

      let createdId = productId;
      if (editing) {
        const response = await postJson(auth, `/api/v1/shop/products/${productId}`, body, 'PATCH');
        if (!response.ok) {
          setProblems([{ step, field: 'name', message: await readProblem(response) }]);
          return;
        }
      } else {
        const response = await postJson(auth, '/api/v1/shop/products', body);
        if (!response.ok) {
          // Checked before the generic handler, because the two need different rendering: one is a
          // field the owner can fix, the other is a purchase decision.
          const refused = await readEntitlementDenial(response);
          if (refused) {
            setDenial(refused);
            return;
          }
          setProblems([{ step, field: 'name', message: await readProblem(response) }]);
          return;
        }
        createdId = ((await response.json()) as { id: string }).id;
      }

      const rows = useVariants && variants.length
        ? variants
        : [{ name: 'Default', price: price, sku: '', stock: stock || '0' }];

      for (const [index, row] of rows.entries()) {
        const variantBody = {
          name: row.name.trim() || `Variant ${index + 1}`,
          price: toPaisa(row.price || price),
          sku: row.sku.trim() || null,
        };
        const created = await postJson(
          auth,
          `/api/v1/shop/products/${createdId}/variants`,
          variantBody,
        );
        if (!created.ok) {
          setProblems([{ step: 'variants', field: 'variants', message: await readProblem(created) }]);
          return;
        }
        const variantId = ((await created.json()) as { id: string }).id;
        const units = Number(row.stock || stock || 0);
        if (units > 0) {
          await postJson(
            auth,
            `/api/v1/shop/stock/products/${createdId}/variants/${variantId}/restock`,
            { units, note: 'Opening stock' },
          );
        }
      }

      navigate('/shop/products');
    } catch (error) {
      setProblems([{ step, field: 'name', message: error instanceof Error ? error.message : 'Could not save the product' }]);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-4 lg:grid-cols-[300px_minmax(0,1fr)]">
        {/* --------------------------------------------------------- stepper */}
        <Card className="h-fit">
          <CardContent className="p-4">
            <ol className="space-y-1">
              {STEPS.map((s) => {
                const Icon = s.icon;
                const active = step === s.key;
                const IconBg = cn(
                  'grid size-9 shrink-0 place-items-center rounded-full transition-colors',
                  active ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
                );
                return (
                  <li key={s.key}>
                    <button
                      type="button"
                      onClick={() => setStep(s.key)}
                      aria-current={active ? 'step' : undefined}
                      className={cn(
                        'flex w-full items-center gap-3 rounded-lg p-2 text-left transition-colors',
                        active ? 'bg-primary' : 'hover:bg-muted',
                      )}
                    >
                      <span className={IconBg}>
                        <Icon className="size-4" aria-hidden />
                      </span>
                      <span className="min-w-0">
                        <span
                          className={cn(
                            'block truncate text-sm font-medium',
                            active ? 'text-primary-foreground' : 'text-foreground',
                          )}
                        >
                          {s.label}
                        </span>
                        <span
                          className={cn(
                            'block truncate text-xs',
                            active ? 'text-primary-foreground/70' : 'text-muted-foreground',
                          )}
                        >
                          {s.hint}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ol>
          </CardContent>
        </Card>

        {/* --------------------------------------------------------- forms */}
        <div className="space-y-4">
          {step === 'info' ? (
            <Card>
              <CardContent className="p-6">
                <h2 className="text-lg font-semibold">Product Information</h2>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  Basic details about your product
                </p>

                <div className="mt-6 space-y-5">
                  {/*
                    * The upgrade prompt sits above the form, not beside the name field.
                    *
                    * <p>An entitlement refusal is not a validation error: nothing about this form
                    * needs correcting. Putting it under the name input reads as "you typed it
                    * wrong", and an owner who believes that will retype it three times before
                    * reading the message again.
                    */}
                  {denial ? <UpgradePrompt denial={denial} /> : null}

                  <Field label="Product Name" htmlFor="product-name" required error={errorFor(problems, 'name')}>
                    <Input
                      id="product-name"
                      required
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      placeholder="Enter product name"
                      maxLength={200}
                    />
                  </Field>

                  <Field
                    label="Product Category"
                    labelId="product-category-label"
                    required
                    error={errorFor(problems, 'category')}
                  >
                    <Select value={category} onValueChange={setCategory}>
                      <SelectTrigger aria-labelledby="product-category-label">
                        <SelectValue placeholder="Select category" />
                      </SelectTrigger>
                      <SelectContent>
                        {(meta?.allowed ?? []).map((c) => (
                          <SelectItem key={c.code} value={c.code}>
                            {c.label}
                          </SelectItem>
                        ))}
                        {/* An empty listbox is indistinguishable from a broken one, so it says so. */}
                        {meta && meta.allowed.length === 0 ? (
                          <SelectItem value="__none" disabled>
                            No categories for {meta.shopType} shops
                          </SelectItem>
                        ) : null}
                      </SelectContent>
                    </Select>
                  </Field>

                  <div className="grid gap-5 sm:grid-cols-2">
                    <Field
                      label="Price (NPR)"
                      htmlFor="product-price"
                      required
                      error={errorFor(problems, 'price')}
                      hint="Entered in rupees; stored in paisa"
                    >
                      <Input
                        id="product-price"
                        required
                        type="number"
                        min="0"
                        step="0.01"
                        value={price}
                        onChange={(e) => setPrice(e.target.value)}
                      />
                    </Field>
                    <Field label="Discount Percentage (%)" htmlFor="product-discount">
                      <Input
                        id="product-discount"
                        type="number"
                        min="0"
                        max="99"
                        step="1"
                        value={discountPct}
                        onChange={(e) => setDiscountPct(e.target.value)}
                      />
                    </Field>
                  </div>
                  {compareAt ? (
                    <p className="-mt-3 text-xs text-muted-foreground">
                      Shopfront price after discount: NRP {(compareAt / 100).toFixed(2)}{' '}
                      <span className="line-through">
                        NRP {(toPaisa(price) / 100).toFixed(2)}
                      </span>
                    </p>
                  ) : null}

                  <Field
                    label="Product Description"
                    htmlFor="product-description"
                    required
                    error={errorFor(problems, 'description')}
                    hint={`${description.length}/1000 characters`}
                  >
                    <Textarea
                      id="product-description"
                      required
                      rows={7}
                      maxLength={1000}
                      value={description}
                      onChange={(e) => setDescription(e.target.value)}
                      placeholder="Describe your product in detail..."
                    />
                  </Field>
                </div>
              </CardContent>
            </Card>
          ) : null}

          {step === 'media' ? (
            <Card>
              <CardContent className="p-6">
                <h2 className="text-lg font-semibold">Product Media</h2>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  Upload product images to Cloudinary. The first image is used as the primary one.
                </p>

                <h3 className="mt-6 text-sm font-semibold">Product Images</h3>

                {!productId ? (
                  <div className="mt-3 rounded-lg border border-dashed p-6 text-center">
                    <ImageUp className="mx-auto size-6 text-muted-foreground" aria-hidden />
                    <p className="mt-2 text-sm font-medium">
                      Images are added after the product is created
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      An image is attached to a product, so it needs one to exist first. Add the
                      product, then reopen it to upload images.
                    </p>
                  </div>
                ) : images.length > 0 ? (
                  <>
                    <ul className="mt-3 grid grid-cols-3 gap-3 sm:grid-cols-4">
                      {images.map((image, index) => (
                        <li key={image.id} className="relative">
                          <img
                            src={image.url}
                            alt={image.altText ?? ''}
                            className="aspect-square w-full rounded-lg border object-cover"
                          />
                          {index === 0 ? (
                            <span className="absolute left-1.5 top-1.5">
                              <Badge className="bg-success text-[10px] text-white">Primary</Badge>
                            </span>
                          ) : null}
                          <button
                            type="button"
                            aria-label={`Remove image ${index + 1}`}
                            onClick={() => removeImage(image.id)}
                            className="absolute right-1.5 top-1.5 rounded bg-black/60 p-1 text-white"
                          >
                            <Trash2 className="size-3.5" aria-hidden />
                          </button>
                        </li>
                      ))}
                    </ul>
                    <p className="mt-2 text-xs text-muted-foreground">
                      Images upload and save immediately. They are not held until you press Add
                      product, so a failed upload is visible while it happens rather than at save.
                    </p>
                  </>
                ) : (
                  <label
                    className={cn(
                      'mt-3 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg',
                      'border-2 border-dashed px-6 py-12 text-center transition-colors hover:bg-muted/40',
                      uploading && 'pointer-events-none opacity-60',
                    )}
                  >
                    <ImageUp className="size-8 text-muted-foreground" aria-hidden />
                    <span className="text-sm">
                      {uploading ? 'Uploading…' : 'Click to upload or drag and drop'}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      PNG, JPG, GIF up to 5MB each
                    </span>
                    <input
                      type="file"
                      accept="image/*"
                      multiple
                      className="sr-only"
                      onChange={(e) => {
                        void uploadImages(e.target.files);
                        e.target.value = '';
                      }}
                    />
                  </label>
                )}

                {uploadError ? (
                  <p role="alert" className="mt-3 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
                    {uploadError}
                  </p>
                ) : null}

                <p className="mt-3 text-xs text-muted-foreground">
                  A product can be published without images; the storefront shows a placeholder
                  rather than a broken image.
                </p>
              </CardContent>
            </Card>
          ) : null}

          {step === 'inventory' ? (
            <Card>
              <CardContent className="p-6">
                <h2 className="text-lg font-semibold">Inventory Management</h2>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  Manage your stock levels to keep up with customer demand
                </p>
                <div className="mt-6 max-w-sm">
                  <Field
                    label="Stock Quantity"
                    htmlFor="product-stock"
                    required={!useVariants}
                    error={errorFor(problems, 'stock')}
                    hint={
                      useVariants
                        ? 'Variants carry their own stock, so this is ignored'
                        : 'Applied to the default variant'
                    }
                  >
                    <Input
                      id="product-stock"
                      required
                      type="number"
                      min="0"
                      step="1"
                      disabled={useVariants}
                      value={stock}
                      onChange={(e) => setStock(e.target.value)}
                      placeholder="Enter quantity"
                    />
                  </Field>
                </div>
              </CardContent>
            </Card>
          ) : null}

          {step === 'variants' ? (
            <Card>
              <CardContent className="p-6">
                <h2 className="text-lg font-semibold">Product Variants</h2>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  Add variants like size or colour, to offer different options for your product.
                </p>

                <div className="mt-6 flex items-center justify-between gap-4 rounded-lg border p-4">
                  <div>
                    <p className="text-sm font-medium">Enable Variants</p>
                    <p className="text-xs text-muted-foreground">
                      Turn on to add variant options like size, colour, etc.
                    </p>
                  </div>
                  <Switch
                    checked={useVariants}
                    onCheckedChange={(on) => {
                      setUseVariants(Boolean(on));
                      if (on && variants.length === 0) {
                        setVariants([{ name: '', price, sku: '', stock: '' }]);
                      }
                    }}
                    aria-label="Enable variants"
                  />
                </div>

                {useVariants ? (
                  <div className="mt-4 space-y-3">
                    {variants.map((variant, index) => (
                      <div key={index} className="grid gap-3 rounded-lg border p-3 sm:grid-cols-12">
                        <div className="sm:col-span-4">
                          <Label className="text-xs">Name</Label>
                          <Input
                            className="mt-1"
                            placeholder="e.g. Large / Red"
                            value={variant.name}
                            onChange={(e) =>
                              updateVariant(index, { name: e.target.value })
                            }
                          />
                        </div>
                        <div className="sm:col-span-3">
                          <Label className="text-xs">Price (NPR)</Label>
                          <Input
                            className="mt-1"
                            type="number"
                            min="0"
                            step="0.01"
                            value={variant.price}
                            onChange={(e) =>
                              updateVariant(index, { price: e.target.value })
                            }
                          />
                        </div>
                        <div className="sm:col-span-2">
                          <Label className="text-xs">SKU</Label>
                          <Input
                            className="mt-1"
                            placeholder="optional"
                            value={variant.sku}
                            onChange={(e) => updateVariant(index, { sku: e.target.value })}
                          />
                        </div>
                        <div className="sm:col-span-2">
                          <Label className="text-xs">Stock</Label>
                          <Input
                            className="mt-1"
                            type="number"
                            min="0"
                            step="1"
                            value={variant.stock}
                            onChange={(e) => updateVariant(index, { stock: e.target.value })}
                          />
                        </div>
                        <div className="flex items-end sm:col-span-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            aria-label={`Remove variant ${index + 1}`}
                            onClick={() => setVariants(variants.filter((_, i) => i !== index))}
                          >
                            <Trash2 className="size-4" aria-hidden />
                          </Button>
                        </div>
                      </div>
                    ))}
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setVariants([...variants, { name: '', price, sku: '', stock: '' }])
                      }
                    >
                      <Plus className="mr-1.5 size-4" aria-hidden />
                      Add variant
                    </Button>
                  </div>
                ) : (
                  <p className="mt-4 rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                    This product will be created with one variant called <strong>Default</strong>.
                  </p>
                )}
              </CardContent>
            </Card>
          ) : null}
        </div>
      </div>

      {/* --------------------------------------------------------- action bar */}
      {problems.length > 0 ? (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          <p className="font-medium">
            Fix {problems.length} problem{problems.length === 1 ? '' : 's'} before saving:
          </p>
          <ul className="mt-1 list-disc pl-5">
            {problems.map((p) => (
              <li key={`${p.field}-${p.message}`}>
                <button
                  type="button"
                  className="underline underline-offset-2"
                  onClick={() => setStep(p.step)}
                >
                  {p.message}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="sticky bottom-0 flex justify-end gap-3 rounded-lg border-t border-border bg-background px-5 py-3">
        <Button
          onClick={() => navigate('/shop/products')}
          style={{ background: '#ef4444', color: '#fff' }}
          className="border-0"
          disabled={saving}
        >
          Discard
        </Button>
        <Button
          onClick={submit}
          disabled={saving}
          style={{ background: '#22c55e', color: '#fff' }}
          className="border-0"
        >
          {saving ? 'Saving…' : editing ? 'Save changes' : 'Add product'}
        </Button>
      </div>
    </div>
  );

  function updateVariant(index: number, patch: Partial<VariantDraft>) {
    setVariants(variants.map((v, i) => (i === index ? { ...v, ...patch } : v)));
  }

  /**
   * Loads an existing product's images when editing.
   *
   * <p>Keyed on the product id and the images themselves, so the list is not re-seeded (and the
   * owner's changes discarded) on every unrelated re-render.
   */
  const loadedFor = `${productId ?? 'new'}:${images.length}`;
  useEffect(() => {
    if (!productId) return;
    let cancelled = false;
    void (async () => {
      const response = await authorizedFetch(auth, `/api/v1/shop/media/for-product/${productId}`);
      if (cancelled || !response.ok) return;
      setImages((await response.json()) as ProductImage[]);
    })();
    return () => {
      cancelled = true;
    };
  }, [auth, productId, loadedFor]);

  /**
   * Uploads images straight to Cloudinary, then records them against the product.
   *
   * <p>The file never passes through our API. The server signs the upload, the browser posts to
   * Cloudinary, and the browser reports the resulting public id back. A backend proxy would buffer
   * every shop's product photography in this process.
   *
   * <p><b>Only possible once the product exists</b>, because an image is attached to a product id.
   * The UI therefore shows the step only in edit mode and says so on a new product — an owner
   * cannot add an image to a product that has no id yet, and pretending otherwise with a fake id
   * would create assets nothing can reach.
   */
  async function uploadImages(files: FileList | null) {
    if (!files || files.length === 0 || !productId) return;
    setUploadError(null);
    setUploading(true);

    try {
      const signResponse = await postJson(auth, '/api/v1/shop/media/signature', { path: 'products' });
      if (!signResponse.ok) throw new Error(await readProblem(signResponse));
      const signature = (await signResponse.json()) as UploadSignature;

      for (const file of Array.from(files)) {
        if (file.size > signature.maxBytes) {
          throw new Error(
            `${file.name} is ${(file.size / 1_048_576).toFixed(1)}MB. ` +
              `The limit is ${(signature.maxBytes / 1_048_576).toFixed(0)}MB.`,
          );
        }

        const publicId = await uploadToCloudinary(file, signature);
        // publicId is why deletion is possible at all (D19): a URL alone would leave every
        // uploaded asset in the Cloudinary account for the life of the project.
        const record = await postJson(auth, '/api/v1/shop/media/record', {
          publicId,
          expectedFolder: signature.folder,
          usage: 'PRODUCT',
          productId,
          variantId: null,
          altText: '',
        });
        if (!record.ok) throw new Error(await readProblem(record));
      }

      const refreshed = await authorizedFetch(auth, `/api/v1/shop/media/for-product/${productId}`);
      if (refreshed.ok) setImages((await refreshed.json()) as ProductImage[]);
    } catch (error) {
      setUploadError(error instanceof Error ? error.message : 'The upload failed');
    } finally {
      setUploading(false);
    }
  }

  /** Removes an image: the Cloudinary asset as well as the row. */
  async function removeImage(id: string) {
    const response = await authorizedFetch(auth, `/api/v1/shop/media/${id}`, { method: 'DELETE' });
    if (response.ok) {
      setImages((current) => current.filter((image) => image.id !== id));
    } else {
      setUploadError(await readProblem(response));
    }
  }
}

/** An image already attached to this product. */
interface ProductImage {
  id: string;
  url: string;
  altText: string | null;
}

/** What the server needs for a direct-to-Cloudinary upload. */
interface UploadSignature {
  timestamp: number;
  signature: string;
  apiKey: string;
  cloudName: string;
  folder: string;
  maxBytes: number;
  maxDimension: number;
}

/**
 * Posts one file to Cloudinary and returns its public id.
 *
 * <p>XMLHttpRequest rather than `fetch`, because `fetch` still has no upload progress event and a
 * progress bar that does not move is worse than none on a slow connection.
 */
function uploadToCloudinary(file: File, signature: UploadSignature): Promise<string> {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('file', file);
    form.append('api_key', signature.apiKey);
    form.append('timestamp', String(signature.timestamp));
    form.append('signature', signature.signature);
    form.append('folder', signature.folder);

    const request = new XMLHttpRequest();
    request.open('POST', `https://api.cloudinary.com/v1_1/${signature.cloudName}/image/upload`);
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) {
        try {
          const body = JSON.parse(request.responseText) as { public_id?: string };
          if (!body.public_id) {
            reject(new Error('Cloudinary did not return a public id for ' + file.name));
            return;
          }
          resolve(body.public_id);
        } catch {
          reject(new Error('Could not read Cloudinary’s response for ' + file.name));
        }
      } else {
        reject(new Error(cloudinaryError(request.responseText, request.status)));
      }
    };
    request.onerror = () => reject(new Error('The network dropped while uploading ' + file.name));
    request.send(form);
  });
}

/** Cloudinary's own message describes the rejected upload; a status code does not. */
function cloudinaryError(body: string, status: number): string {
  try {
    const parsed = JSON.parse(body) as { error?: { message?: string } };
    if (parsed.error?.message) return parsed.error.message;
  } catch {
    // Not JSON.
  }
  return `Cloudinary rejected the upload (HTTP ${status})`;
}

// ---------------------------------------------------------------------- helpers

/**
 * A labelled form field.
 *
 * <p>The `htmlFor`/`id` pair is not optional. Without it the input has **no accessible name**, so a
 * screen reader announces "edit text, blank" and a test cannot find it by label either — which is
 * exactly how this was caught.
 */
function Field({
  label,
  required,
  hint,
  error,
  htmlFor,
  labelId,
  children,
}: {
  label: string;
  required?: boolean;
  hint?: string;
  error?: string;
  /** Set for real labelable inputs: the `<label for>` target. */
  htmlFor?: string;
  /** Set for composite widgets (the Radix Select) that `<label for>` cannot name. */
  labelId?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <Label htmlFor={htmlFor} id={labelId} className="flex items-center gap-1">
        {label}
        {required ? (
          <span className="text-red-500" aria-hidden>
            *
          </span>
        ) : null}
      </Label>
      <div className="mt-1.5">{children}</div>
      {error ? (
        <p className="mt-1 text-xs text-destructive" role="alert">
          {error}
        </p>
      ) : null}
      {hint && !error ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

/** The message for one field, so it can sit under the input it belongs to. */
function errorFor(problems: Problem[], field: Problem['field']): string | undefined {
  return problems.find((p) => p.field === field)?.message;
}

/** Rupees in the form to minor units, which is what the whole system stores. */
function toPaisa(rupees: string): number {
  const value = Number(rupees);
  return Number.isFinite(value) && value >= 0 ? Math.round(value * 100) : 0;
}

/** One thing wrong with one field. */
interface Problem {
  /** Which step owns it, so the form can jump there. */
  step: string;
  /** The field name, so the message can sit under the right input. */
  field: 'name' | 'category' | 'price' | 'discount' | 'description' | 'stock' | 'variants';
  message: string;
}

/**
 * Client-side validation, mirroring the server's rules.
 *
 * <p>Not a substitute for it — the server still rejects anything invalid — but an owner who has
 * typed a category the server will refuse should not have to save first to find out.
 *
 * <p>Returns structured problems rather than strings. The previous version returned English and
 * then decided which step to show by `message.includes('name')`, which is case-sensitive: an empty
 * submit matched none of them and the form **jumped to the Inventory step**, hiding the four fields
 * that were actually empty. Keying off the field makes that impossible.
 */
function validate(input: {
  name: string;
  category: string;
  price: string;
  description: string;
  stock: string;
  useVariants: boolean;
  variants: VariantDraft[];
}): Problem[] {
  const problems: Problem[] = [];
  if (!input.name.trim()) {
    problems.push({ step: 'info', field: 'name', message: 'Product Name is required' });
  }
  if (!input.category) {
    problems.push({ step: 'info', field: 'category', message: 'Product Category is required' });
  }
  if (toPaisa(input.price) <= 0) {
    problems.push({ step: 'inventory', field: 'price', message: 'Price must be greater than zero' });
  }
  if (!input.description.trim()) {
    problems.push({
      step: 'info',
      field: 'description',
      message: 'Product Description is required',
    });
  }
  if (!input.useVariants && (!Number.isFinite(Number(input.stock)) || Number(input.stock) < 0)) {
    problems.push({
      step: 'inventory',
      field: 'stock',
      message: 'Stock Quantity must be zero or more',
    });
  }
  if (input.useVariants) {
    if (input.variants.length === 0) {
      problems.push({
        step: 'variants',
        field: 'variants',
        message: 'Add at least one variant, or turn variants off',
      });
    }
    input.variants.forEach((v, i) => {
      if (toPaisa(v.price) <= 0) {
        problems.push({
          step: 'variants',
          field: 'variants',
          message: `Variant ${i + 1} needs a price above zero`,
        });
      }
    });
    const skus = input.variants.map((v) => v.sku.trim().toLowerCase()).filter(Boolean);
    if (new Set(skus).size !== skus.length) {
      problems.push({
        step: 'variants',
        field: 'variants',
        message: 'Two variants share a SKU',
      });
    }
  }
  return problems;
}

/** Turns a problem response into one readable line, rather than "Request failed: 400". */
async function readProblem(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; issues?: string[] };
    if (Array.isArray(body.issues) && body.issues.length) return body.issues.join('; ');
    if (body.detail) return body.detail;
  } catch {
    // Fall through to the status text.
  }
  return `The server rejected this (HTTP ${response.status})`;
}
