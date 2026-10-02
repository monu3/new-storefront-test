import type { ComponentType, SVGProps } from 'react';
import {
  LuBaby, LuBookOpen, LuCupSoda, LuDumbbell, LuFlower, LuGem,
  LuLaptop, LuLeaf, LuPawPrint, LuPill, LuScissors, LuShirt, LuSofa,
  LuShoppingBasket, LuSmartphone, LuSparkles, LuStore, LuToyBrick, LuTv,
} from 'react-icons/lu';
import { FiAlertCircle, FiCheckCircle, FiClock, FiPackage, FiShoppingCart, FiTruck, FiXCircle } from 'react-icons/fi';
import { HiOutlineArrowPath } from 'react-icons/hi2';

export type IconComponent = ComponentType<SVGProps<SVGSVGElement>>;

/**
 * Icons used by shop types, matching the `icon` field the meta API returns.
 *
 * Curated rather than importing the whole library: a bare `import * as Icons` would pull
 * several megabytes into both bundles for the dozen glyphs actually rendered. The API returns
 * a name, and only names in this map render — an unknown name falls back rather than crashing
 * a storefront.
 */
export const SHOP_TYPE_ICONS: Record<string, IconComponent> = {
  Pill: LuPill,
  Shirt: LuShirt,
  ShoppingBasket: LuShoppingBasket,
  Laptop: LuLaptop,
  Leaf: LuLeaf,
  Baby: LuBaby,
  Smartphone: LuSmartphone,
  Sparkles: LuSparkles,
  Gem: LuGem,
  Armchair: LuSofa,
  Home: LuStore,
  BookOpen: LuBookOpen,
  Dumbbell: LuDumbbell,
  ToyBrick: LuToyBrick,
  PawPrint: LuPawPrint,
  Flower: LuFlower,
  Tv: LuTv,
  CupSoda: LuCupSoda,
  Scissors: LuScissors,
};

/** Operational icons shared across order status, notifications and dashboards. */
export const STATUS_ICONS: Record<string, IconComponent> = {
  PENDING: FiClock,
  ACCEPTED: FiCheckCircle,
  PROCESSING: FiPackage,
  SHIPPED: FiTruck,
  DELIVERED: FiCheckCircle,
  CANCELLED: FiXCircle,
  RETURNED: FiAlertCircle,
  REFUNDED: HiOutlineArrowPath,
};

export const UI_ICONS = {
  cart: FiShoppingCart,
  package: FiPackage,
  truck: FiTruck,
  check: FiCheckCircle,
  alert: FiAlertCircle,
  close: FiXCircle,
  clock: FiClock,
  refresh: HiOutlineArrowPath,
} as const;

export function shopTypeIcon(name: string | undefined): IconComponent {
  return (name && SHOP_TYPE_ICONS[name]) || LuShoppingBasket;
}

export function statusIcon(name: string): IconComponent {
  return STATUS_ICONS[name] ?? FiClock;
}
