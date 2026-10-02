import { z } from 'zod';

import { PRODUCT_CATEGORY_CODES, SHOP_TYPE_CODES } from '@store-app/enums';

/**
 * Validation shared between the signup form, the product editor and the API payload.
 *
 * One schema per concept, reused in all three places. A duplicated rule is a rule that will
 * eventually disagree with itself — the form accepts something the server rejects, or worse,
 * the server accepts something the form never sends.
 *
 * Enums are derived from `@store-app/enums` so adding a category in Java and here stays a
 * two-line change rather than a hunt through every form.
 */

export const emailSchema = z.string().trim().toLowerCase().email('Enter a valid email address');

/** Nepalese mobile: 10 digits starting 97/98. Kept permissive enough for landlines via a flag. */
export const nepalPhoneSchema = z
  .string()
  .trim()
  .regex(/^(97|98)\d{8}$/, 'Enter a 10-digit Nepali mobile number starting 97 or 98');

/**
 * Money input is a rupee amount a human typed, converted to paisa on submit.
 *
 * Validated as a string first: `z.coerce.number()` on an empty field yields 0, which silently
 * turns "left blank" into "free". A product priced at zero is a real, separate decision.
 */
export const rupeeAmountSchema = z
  .string()
  .trim()
  .min(1, 'Price is required')
  .regex(/^\d+(\.\d{1,2})?$/, 'Enter an amount with at most two decimal places')
  .refine((v) => Number(v) > 0, 'Price must be greater than zero');

/** Rupee string to integer paisa. The only place that conversion happens. */
export function rupeesToMinor(rupees: string): number {
  return Math.round(Number(rupees) * 100);
}

export function minorToRupees(minor: number): string {
  return (minor / 100).toFixed(2);
}

export const shopSignupSchema = z.object({
  businessName: z.string().trim().min(2, 'Enter your shop name').max(120),
  shopType: z.enum(SHOP_TYPE_CODES, {
    errorMap: () => ({ message: 'Choose the type of shop you run' }),
  }),
  contactName: z.string().trim().min(2, 'Enter a contact name').max(120),
  email: emailSchema,
  phone: nepalPhoneSchema,
  district: z.string().trim().min(2, 'Select your district'),
  municipality: z.string().trim().min(2, 'Enter your municipality or ward'),
  /** Free-text to suggest a slug; the server de-duplicates and may adjust it. */
  slugPreference: z
    .string()
    .trim()
    .toLowerCase()
    .regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/, 'Use lowercase letters, numbers and hyphens only')
    .min(3)
    .max(50)
    .optional(),
});

export type ShopSignupInput = z.infer<typeof shopSignupSchema>;

export const productDraftSchema = z
  .object({
    name: z.string().trim().min(2, 'Enter a product name').max(200),
    productCategory: z.enum(PRODUCT_CATEGORY_CODES, {
      errorMap: () => ({ message: 'Choose a category' }),
    }),
    price: rupeeAmountSchema,
    compareAtPrice: z
      .string()
      .trim()
      .regex(/^\d+(\.\d{1,2})?$/, 'Enter a valid amount')
      .optional()
      .or(z.literal('')),
    shortDescription: z.string().trim().max(300).optional().or(z.literal('')),
    description: z.string().trim().max(8000).optional().or(z.literal('')),
    weightGrams: z.coerce.number().int().nonnegative().optional(),
    trackInventory: z.boolean().default(true),
    variants: z
      .array(
        z.object({
          name: z.string().trim().min(1, 'Name the variant, e.g. "Red / Large"'),
          price: rupeeAmountSchema,
          sku: z.string().trim().optional().or(z.literal('')),
          optionValues: z.record(z.string()).default({}),
        }),
      )
      .optional(),
  })
  .refine(
    (v) => !v.compareAtPrice || Number(v.compareAtPrice) > Number(v.price),
    {
      message: 'The "was" price must be higher than the current price',
      path: ['compareAtPrice'],
    },
  );

export type ProductDraftInput = z.infer<typeof productDraftSchema>;

/**
 * The variant builder produces `{ color: ["Red","Blue"], size: ["M","L"] }`, which the backend
 * expands into a product x variant matrix. Keys and values are both non-empty strings; the
 * refinement on the value schema is what rejects an empty option.
 */
const optionValues = z.array(z.string().trim().min(1, 'Option values cannot be blank'));

export const variantMatrixSchema = z.record(
  z.string().trim().min(1),
  optionValues,
);

export const addressSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter the recipient name'),
  phone: nepalPhoneSchema,
  province: z.string().trim().min(1, 'Select a province'),
  district: z.string().trim().min(1, 'Select a district'),
  municipality: z.string().trim().min(1, 'Enter municipality or ward'),
  tole: z.string().trim().optional().or(z.literal('')),
  street: z.string().trim().min(3, 'Enter the street or landmark'),
  postalCode: z
    .string()
    .trim()
    .regex(/^\d{4,5}$/, 'Enter a 4 or 5 digit postal code')
    .optional()
    .or(z.literal('')),
});

export type AddressInput = z.infer<typeof addressSchema>;

export const checkoutSchema = z.object({
  address: addressSchema,
  paymentMethod: z.enum(['ESAWA', 'COD', 'BANK_TRANSFER']),
  customerNote: z.string().trim().max(500).optional().or(z.literal('')),
  guestEmail: emailSchema.optional().or(z.literal('')),
});

export type CheckoutInput = z.infer<typeof checkoutSchema>;

export const walletTopupSchema = z.object({
  amount: rupeeAmountSchema,
  method: z.enum(['ESAWA', 'BANK_TRANSFER']),
  /** The transaction code or transfer reference, shown to the admin for approval. */
  reference: z.string().trim().min(4, 'Enter the transaction or reference number'),
  note: z.string().trim().max(300).optional().or(z.literal('')),
});

export type WalletTopupInput = z.infer<typeof walletTopupSchema>;

export const discountSchema = z.object({
  name: z.string().trim().min(2, 'Give the discount a name'),
  code: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^[A-Z0-9_-]{3,24}$/, 'Use 3-24 letters, numbers, dashes or underscores')
    .optional()
    .or(z.literal('')),
  type: z.enum(['PERCENT', 'FIXED', 'FREE_SHIPPING']),
  value: z.coerce.number().nonnegative(),
  minOrder: z.string().trim().optional().or(z.literal('')),
  maxDiscount: z.string().trim().optional().or(z.literal('')),
  startsAt: z.string().optional().or(z.literal('')),
  endsAt: z.string().optional().or(z.literal('')),
});

export type DiscountInput = z.infer<typeof discountSchema>;
