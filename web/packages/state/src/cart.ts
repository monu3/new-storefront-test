import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface CartLine {
  /** The variant, not the product: a t-shirt's price depends on size. */
  variantId: string;
  productId: string;
  shopSlug: string;
  name: string;
  variantName?: string;
  imageUrl?: string;
  quantity: number;
  /** Cached for instant render only. Never trusted at checkout. */
  unitPriceMinor: number;
}

interface CartState {
  lines: CartLine[];
  /** Carts are per shop: a single basket spanning two shops cannot be one order. */
  activeShopSlug: string | null;
  add(line: Omit<CartLine, 'quantity'>, quantity?: number): void;
  setQuantity(variantId: string, quantity: number): void;
  remove(variantId: string): void;
  clear(): void;
  clearOtherShops(activeShopSlug: string): void;
  count(): number;
  subtotalMinor(): number;
}

export const useCart = create<CartState>()(
  persist(
    (set, get) => ({
      lines: [],
      activeShopSlug: null,

      add(line, quantity = 1) {
        const { activeShopSlug } = get();

        // Switching shops discards the old basket rather than silently mixing vendors into one
        // order, which the backend could not fulfil.
        if (activeShopSlug && activeShopSlug !== line.shopSlug) {
          set({ lines: [], activeShopSlug: line.shopSlug });
        }

        set((state) => {
          const existing = state.lines.find((l) => l.variantId === line.variantId);
          if (existing) {
            return {
              activeShopSlug: line.shopSlug,
              lines: state.lines.map((l) =>
                l.variantId === line.variantId
                  ? { ...l, quantity: Math.min(l.quantity + quantity, 99) }
                  : l,
              ),
            };
          }
          return {
            activeShopSlug: line.shopSlug,
            lines: [...state.lines, { ...line, quantity: Math.min(quantity, 99) }],
          };
        });
      },

      setQuantity(variantId, quantity) {
        set((state) => ({
          lines:
            quantity <= 0
              ? state.lines.filter((l) => l.variantId !== variantId)
              : state.lines.map((l) =>
                  l.variantId === variantId
                    ? { ...l, quantity: Math.min(quantity, 99) }
                    : l,
                ),
        }));
      },

      remove(variantId) {
        set((state) => ({ lines: state.lines.filter((l) => l.variantId !== variantId) }));
      },

      clear() {
        set({ lines: [], activeShopSlug: null });
      },

      clearOtherShops(activeShopSlug) {
        set({ lines: get().lines.filter((l) => l.shopSlug === activeShopSlug) });
      },

      count() {
        return get().lines.reduce((sum, l) => sum + l.quantity, 0);
      },

      subtotalMinor() {
        return get().lines.reduce((sum, l) => sum + l.unitPriceMinor * l.quantity, 0);
      },
    }),
    {
      name: 'store-app-cart',
      // Price is persisted only so the cart page can render before the server responds; the
      // checkout quote always re-prices from scratch.
      partialize: (state) => ({
        lines: state.lines,
        activeShopSlug: state.activeShopSlug,
      }),
    },
  ),
);
