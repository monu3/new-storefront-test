/**
 * Types mirroring the backend's hardcoded enums.
 *
 * These are declared as string-literal unions rather than `string` on purpose: the whole point
 * of D17/D18 is that the valid set is closed and known at compile time, so a typo in a
 * category code is a TypeScript error instead of a 400 from the server.
 *
 * The runtime list still comes from the API. The union here is a compile-time contract, not a
 * second copy of the data — if a category is added in Java and not here, the API still returns
 * it and the UI still works, it just is not autocomplete-checked yet.
 */

export const SHOP_TYPE_CODES = [
  'PHARMACY',
  'FASHION',
  'GROCERY',
  'TECH',
  'PLANT_SHOP',
  'BABY_SHOP',
  'MOBILE_SHOP',
  'COSMETICS',
  'JEWELLERY',
  'FURNITURE',
  'HOME_DECOR',
  'BOOKS',
  'SPORTS',
  'TOYS',
  'PET_SUPPLIES',
  'FLOWERS',
  'ELECTRONICS',
  'GROCERY_BEVERAGES',
  'BEAUTY_SERVICES',
] as const;

export type ShopTypeCode = (typeof SHOP_TYPE_CODES)[number];

export const PRODUCT_CATEGORY_CODES = [
  // Pharmacy
  'MEDICINE', 'MEDICAL_DEVICE', 'SUPPLEMENT', 'PERSONAL_CARE', 'BABY_CARE',
  // Fashion
  'MENS_CLOTHING', 'WOMENS_CLOTHING', 'KIDS_CLOTHING', 'FOOTWEAR', 'ACCESSORIES',
  'BAGS', 'JEWELRY', 'WATCHES', 'HANDBAG',
  // Grocery
  'RICE', 'LENTIL', 'SPICES', 'OIL', 'SNACKS', 'DAIRY', 'TEA', 'COFFEE',
  'SUGAR_SALT', 'MASALA',
  // Tech
  'LAPTOP', 'DESKTOP', 'TECH_ACCESSORY', 'PERIPHERAL', 'CAMERA', 'AUDIO',
  'STORAGE', 'NETWORKING',
  // Plant shop
  'INDOOR_PLANT', 'OUTDOOR_PLANT', 'SEED', 'SOIL', 'FERTILIZER', 'PLANTER', 'POTTING_MIX',
  // Baby shop
  'DIAPER', 'BABY_FOOD', 'BABY_CLOTHING', 'STROLLER', 'BABY_TOY', 'BABY_FURNITURE',
  // Mobile shop
  'SMARTPHONE', 'FEATURE_PHONE', 'TABLET', 'SMARTWATCH', 'MOBILE_ACCESSORY', 'SIM_CARD', 'EARPHONE',
  // Cosmetics
  'SKINCARE', 'HAIRCARE', 'MAKEUP', 'FRAGRANCE', 'NAIL_CARE', 'SUNSCREEN',
  // Jewellery
  'RING', 'NECKLACE', 'EARRING', 'BRACELET', 'BANGLES', 'WEDDING_SET',
  'SILVER_JEWELRY', 'GOLD_JEWELRY',
  // Furniture
  'SOFA', 'BED', 'TABLE', 'CHAIR', 'WARDROBE', 'MATTRESS', 'TV_UNIT', 'OFFICE_DESK',
  // Home decor
  'WALL_DECOR', 'LIGHTING', 'RUG', 'CURTAIN', 'VASE', 'CUSHION', 'WALLPAPER', 'MIRROR',
  // Books
  'FICTION', 'NON_FICTION', 'ACADEMIC', 'CHILDREN_BOOK', 'RELIGIOUS_BOOK', 'NOTEBOOK', 'STATIONERY',
  // Sports
  'SPORTS_FOOTWEAR', 'GYM_EQUIPMENT', 'SPORTSWEAR', 'BICYCLE', 'SPORTS_ACCESSORY', 'TEAM_SPORT',
  // Toys
  'BOARD_GAME', 'SOFT_TOY', 'BUILDING_TOY', 'PUZZLE', 'REMOTE_CONTROL', 'EDUCATIONAL_TOY',
  // Pet supplies
  'PET_FOOD', 'PET_TOY', 'PET_ACCESSORY', 'PET_CARE', 'AQUARIUM',
  // Flowers
  'ROSE', 'ORCHID', 'TULIP', 'LILY', 'BOUQUET', 'POTTED_FLOWER', 'FLOWER_DECOR',
  // Electronics
  'TELEVISION', 'AUDIO_SYSTEM', 'CAMERA_ELECTRONIC', 'GADGET', 'HOME_APPLIANCE', 'SMARTHOME',
  // Beverages
  'JUICE', 'SOFT_DRINK', 'WATER', 'ENERGY_DRINK', 'MILK', 'TEA_BEVERAGE', 'COFFEE_BEVERAGE',
  // Beauty services
  'HAIRCUT', 'SPA', 'MASSAGE', 'NAIL_SALON', 'SKIN_TREATMENT', 'BRIDAL_MAKEUP',
] as const;

export type ProductCategoryCode = (typeof PRODUCT_CATEGORY_CODES)[number];

export interface CategoryResponse {
  code: ProductCategoryCode;
  label: string;
  /** URL-safe handle, e.g. `medicine` for `/s/acme/c/medicine`. */
  slug: string;
}

export interface ShopTypeResponse {
  code: ShopTypeCode;
  label: string;
  /** react-icons export name, so the UI renders the same glyph the API names. */
  icon: string;
  iconGroup: string;
  categories: CategoryResponse[];
}

/** Narrowing helpers, for parsing route params and persisted state safely. */
export function isShopTypeCode(value: string): value is ShopTypeCode {
  return (SHOP_TYPE_CODES as readonly string[]).includes(value);
}

export function isProductCategoryCode(value: string): value is ProductCategoryCode {
  return (PRODUCT_CATEGORY_CODES as readonly string[]).includes(value);
}

/** True when a shop of this type may sell the given category (D18). */
export function shopTypeAllows(
  type: ShopTypeResponse | undefined,
  category: ProductCategoryCode,
): boolean {
  return type?.categories.some((c) => c.code === category) ?? false;
}
