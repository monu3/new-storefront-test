import { api } from './client';

/**
 * The shop dashboard (Phase 4).
 *
 * Money arrives twice: `totalRevenueDisplay` for the card, `totalRevenueMinor` for anything that
 * has to compute with it. Shipping both means the client never re-implements the formatting and
 * gets it subtly wrong — which is exactly what happened on the storefront when a raw JPA entity
 * went over the wire.
 *
 * A `*ChangePct` of `null` means there was no previous period to compare against. It is deliberately
 * not `0`, which would assert "no change" — a claim there is no data for.
 */
export interface MonthRevenue {
  month: string;
  amountMinor: number;
}

export interface MonthSales {
  month: string;
  orders: number;
}

export interface Dashboard {
  shopSlug: string;
  shopName: string;
  shopType: string;
  logoUrl: string | null;
  verified: boolean;
  totalRevenueDisplay: string;
  totalRevenueMinor: number;
  revenueChangePct: number | null;
  sales: number;
  salesChangePct: number | null;
  newCustomers: number;
  newCustomersChangePct: number | null;
  unitsSold: number;
  unitsSoldChangePct: number | null;
  products: number;
  publishedProducts: number;
  draftProducts: number;
  lowStockVariants: number;
  openOrders: number;
  followers: number;
  revenueByMonth: MonthRevenue[];
  salesByMonth: MonthSales[];
}

export const dashboard = {
  summary: () => api.get<Dashboard>('/api/v1/shop/dashboard'),
};
