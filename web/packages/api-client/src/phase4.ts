/**
 * Phase 4 types: settings, discounts, audience, payments, invoices.
 *
 * <p>These mirror the backend records exactly, including the fields whose whole purpose is to stop
 * the console guessing — `usableNow`, `canBeEnabled`, `remainingUses`, `hasEsewaSecret`. Each one
 * is a decision the server has already made; a console that recomputes them would eventually
 * disagree with the checkout that refuses a discount or the gateway that refuses a payment.
 *
 * <p>Money is `bigint` paisa on the wire. Amounts are never floats anywhere in this platform, and
 * the browser is the one place a float could quietly appear — so the integer arrives and the
 * display string arrives with it, and nothing here converts between them.
 */

/**
 * A page, in the shape the API returns for its offset-paged endpoints.
 *
 * <p>Named `OffsetPage` rather than `Page` because `client.ts` already exports a `Page<T>`, and
 * that one is a cursor page: `items` plus `nextCursor`. Two shapes, two names. Re-exporting both as
 * `Page` would make `import { Page } from '@store-app/api-client'` ambiguous for every caller,
 * and the compiler's suggested fix — an explicit re-export — would then have to be repeated at each
 * import site, which is the arrangement where one eventually gets it wrong.
 */
export interface OffsetPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ---------------------------------------------------------------- settings

export interface GeneralSettings {
  displayName: string | null;
  tagline: string | null;
  about: string | null;
  supportEmail: string | null;
  supportPhone: string | null;
  /** Always `NPR` in this build. The server validates it rather than trusting the form. */
  currency: string;
  timezone: string;
}

export interface AddressSettings {
  line1: string | null;
  line2: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  country: string | null;
  deliveryInstructions: string | null;
}

export interface SocialSettings {
  facebookUrl: string | null;
  instagramUrl: string | null;
  whatsappUrl: string | null;
  youtubeUrl: string | null;
  tiktokUrl: string | null;
  websiteUrl: string | null;
}

export interface CheckoutSettings {
  acceptOrders: boolean;
  minOrderMinor: number | null;
  autoAcceptOrders: boolean;
  showDeliveryEstimate: boolean;
  guestCheckoutAllowed: boolean;
  checkoutNote: string | null;
}

export interface NotificationSettings {
  newOrder: boolean;
  lowStock: boolean;
  lowStockThreshold: number | null;
  emailDigest: boolean;
}

export interface OrderNumberSettings {
  format: string;
  next: number | null;
}

export interface InvoiceTemplateSettings {
  showLogo: boolean;
  showSignatureLine: boolean;
  showTaxBreakdown: boolean;
  showPaymentMethod: boolean;
  /** A six-digit hex colour. The server rejects anything else. */
  accentColor: string;
  footerNote: string | null;
  businessTaxId: string | null;
}

export interface EmailTemplateSettings {
  orderConfirmationSubject: string | null;
  orderConfirmationBody: string | null;
  orderShippedSubject: string | null;
  orderShippedBody: string | null;
  lowStockSubject: string | null;
  lowStockBody: string | null;
}

export interface ShopSettings {
  general: GeneralSettings;
  address: AddressSettings;
  social: SocialSettings;
  checkout: CheckoutSettings;
  notifications: NotificationSettings;
  orderNumber: OrderNumberSettings;
  invoiceTemplate: InvoiceTemplateSettings;
  emailTemplates: EmailTemplateSettings;
}

// ---------------------------------------------------------------- discounts

export type DiscountType = 'PERCENT' | 'FIXED' | 'FREE_SHIPPING';

export interface Discount {
  id: string;
  /** Null means an automatic discount: applied without the customer entering anything. */
  code: string | null;
  name: string;
  type: DiscountType;
  /**
   * Basis points for `PERCENT` (1000 = 10%), minor units for `FIXED`, always 0 for
   * `FREE_SHIPPING`. Meaning depends on `type`, which is why the two are not separate fields.
   */
  value: number;
  minOrderMinor: number | null;
  maxDiscountMinor: number | null;
  usageLimit: number | null;
  usageUsed: number;
  perCustomerLimit: number | null;
  startsAt: string | null;
  endsAt: string | null;
  active: boolean;
  /** Whether the server would accept this code today. Not something the console decides. */
  usableNow: boolean;
  /** Why it is not usable, when it is not. Null when it is. */
  notUsableReason: string | null;
  /** Remaining uses for the progress bar. Null when unlimited. */
  remainingUses: number | null;
}

export interface DiscountDraft {
  code: string | null;
  name: string;
  type: DiscountType;
  value: number;
  minOrderMinor: number | null;
  maxDiscountMinor: number | null;
  usageLimit: number | null;
  perCustomerLimit: number | null;
  startsAt: string | null;
  endsAt: string | null;
  active: boolean;
}

// ---------------------------------------------------------------- audience

export interface AudienceCustomer {
  id: string;
  email: string;
  /** Full name if known, otherwise the email's local part. Never blank, so a table cell is not. */
  displayName: string;
  fullName: string | null;
  phone: string | null;
  orders: number;
  totalSpentMinor: number;
  totalSpentDisplay: string;
  averageOrderMinor: number;
  lastOrderAt: string | null;
  firstSeenAt: string | null;
  /** Shop-private. Never returned to the customer anywhere in the platform. */
  notes: string | null;
  notesUpdatedAt: string | null;
  hasOrdered: boolean;
}

export interface AudienceSummary {
  customers: number;
  buyers: number;
  orders: number;
  spentMinor: number;
  averageOrderMinor: number;
  /** Percentage of buyers with two or more orders. */
  repeatRate: number;
}

export type AudienceSort = 'SPENT' | 'RECENT' | 'FIRST_SEEN';

// ---------------------------------------------------------------- payments

export type PaymentMethodCode = 'ESAWA' | 'COD' | 'BANK_TRANSFER';

export interface PaymentMethod {
  method: PaymentMethodCode;
  displayLabel: string | null;
  /** What the customer sees: the owner's label, or the gateway's own name. */
  customerLabel: string;
  enabled: boolean;
  /** Whether this method has everything it needs to take a payment. */
  canBeEnabled: boolean;
  /** What is missing, when `canBeEnabled` is false. Lets the toggle carry its own reason. */
  missingConfiguration: string | null;
  sortOrder: number;
  // eSewa
  esewaMerchantId: string | null;
  /** Never the secret itself — a console that is never sent it cannot leak it. */
  hasEsewaSecret: boolean;
  // Bank transfer
  bankName: string | null;
  bankAccountName: string | null;
  hasBankAccountNumber: boolean;
  // Shared
  codFeeMinor: number;
  minimumMinor: number;
  maxOrderMinor: number | null;
  instructions: string | null;
}

export interface PaymentMethods {
  /**
   * Whether this build can store secrets at all. When false the console must disable the secret
   * fields rather than collect a value the server will refuse.
   */
  secretsAvailable: boolean;
  methods: PaymentMethod[];
}

export interface PaymentMethodDraft {
  esewaMerchantId: string | null;
  /** Write-only. Omit or leave empty to keep the stored secret. */
  esewaSecret: string | null;
  bankName: string | null;
  bankAccountName: string | null;
  /** Write-only. Omit or leave empty to keep the stored number. */
  bankAccountNumber: string | null;
  codFeeMinor: number;
  minimumMinor: number;
  maxOrderMinor: number | null;
  displayLabel: string | null;
  instructions: string | null;
  sortOrder: number;
  enabled: boolean;
}

// ---------------------------------------------------------------- bulk transitions

/**
 * The result of a bulk transition.
 *
 * <p>`refused` is a map rather than a count because the console has to say *which* order was left
 * behind. "3 of 4 succeeded" leaves the owner unable to act, and the one left behind is the one
 * that needs them.
 */
export interface BulkTransitionResult {
  moved: string[];
  refused: Record<string, string>;
  requested: number;
  allSucceeded: boolean;
}