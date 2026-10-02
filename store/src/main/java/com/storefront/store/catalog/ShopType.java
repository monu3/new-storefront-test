package com.storefront.store.catalog;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The vertical a shop operates in, chosen once at signup (plan decision D17).
 *
 * <p>This is a hardcoded enum, not a table. There is no admin UI for creating shop types and
 * no sub-category level: the two-category taxonomy in v1 of the plan was replaced precisely to
 * remove that surface. Adding a vertical is a code change and a redeploy.
 *
 * <p>Each constant declares the {@link ProductCategory} values a shop of that type may use.
 * That declaration is the <em>single</em> place the rule lives, which is what lets a pharmacy
 * be structurally incapable of listing a KURTA.
 */
public enum ShopType {

	PHARMACY("Pharmacy", "Pill", IconCategory.CARE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.MEDICINE,
					ProductCategory.MEDICAL_DEVICE,
					ProductCategory.SUPPLEMENT,
					ProductCategory.PERSONAL_CARE,
					ProductCategory.BABY_CARE);
		}
	},

	FASHION("Fashion", "Shirt", IconCategory.APPAREL) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.MENS_CLOTHING,
					ProductCategory.WOMENS_CLOTHING,
					ProductCategory.KIDS_CLOTHING,
					ProductCategory.FOOTWEAR,
					ProductCategory.ACCESSORIES,
					ProductCategory.BAGS,
					ProductCategory.JEWELRY,
					ProductCategory.WATCHES,
					ProductCategory.HANDBAG);
		}
	},

	GROCERY("Grocery", "ShoppingBasket", IconCategory.STORE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.RICE,
					ProductCategory.LENTIL,
					ProductCategory.SPICES,
					ProductCategory.OIL,
					ProductCategory.SNACKS,
					ProductCategory.DAIRY,
					ProductCategory.TEA,
					ProductCategory.COFFEE,
					ProductCategory.SUGAR_SALT,
					ProductCategory.MASALA);
		}
	},

	TECH("Tech", "Laptop", IconCategory.ELECTRONICS) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.LAPTOP,
					ProductCategory.DESKTOP,
					ProductCategory.TECH_ACCESSORY,
					ProductCategory.PERIPHERAL,
					ProductCategory.CAMERA,
					ProductCategory.AUDIO,
					ProductCategory.STORAGE,
					ProductCategory.NETWORKING);
		}
	},

	PLANT_SHOP("Plant Shop", "Leaf", IconCategory.NATURE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.INDOOR_PLANT,
					ProductCategory.OUTDOOR_PLANT,
					ProductCategory.SEED,
					ProductCategory.SOIL,
					ProductCategory.FERTILIZER,
					ProductCategory.PLANTER,
					ProductCategory.POTTING_MIX);
		}
	},

	BABY_SHOP("Baby Shop", "Baby", IconCategory.CARE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.DIAPER,
					ProductCategory.BABY_FOOD,
					ProductCategory.BABY_CLOTHING,
					ProductCategory.STROLLER,
					ProductCategory.BABY_TOY,
					ProductCategory.BABY_CARE,
					ProductCategory.BABY_FURNITURE);
		}
	},

	MOBILE_SHOP("Mobile Shop", "Smartphone", IconCategory.ELECTRONICS) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.SMARTPHONE,
					ProductCategory.FEATURE_PHONE,
					ProductCategory.TABLET,
					ProductCategory.SMARTWATCH,
					ProductCategory.MOBILE_ACCESSORY,
					ProductCategory.SIM_CARD,
					ProductCategory.EARPHONE);
		}
	},

	COSMETICS("Cosmetics", "Sparkles", IconCategory.CARE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.SKINCARE,
					ProductCategory.HAIRCARE,
					ProductCategory.MAKEUP,
					ProductCategory.FRAGRANCE,
					ProductCategory.NAIL_CARE,
					ProductCategory.SUNSCREEN);
		}
	},

	JEWELLERY("Jewellery", "Gem", IconCategory.ACCESSORY) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.RING,
					ProductCategory.NECKLACE,
					ProductCategory.EARRING,
					ProductCategory.BRACELET,
					ProductCategory.BANGLES,
					ProductCategory.WEDDING_SET,
					ProductCategory.SILVER_JEWELRY,
					ProductCategory.GOLD_JEWELRY);
		}
	},

	FURNITURE("Furniture", "Armchair", IconCategory.HOME) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.SOFA,
					ProductCategory.BED,
					ProductCategory.TABLE,
					ProductCategory.CHAIR,
					ProductCategory.WARDROBE,
					ProductCategory.MATTRESS,
					ProductCategory.TV_UNIT,
					ProductCategory.OFFICE_DESK);
		}
	},

	HOME_DECOR("Home Decor", "Home", IconCategory.HOME) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.WALL_DECOR,
					ProductCategory.LIGHTING,
					ProductCategory.RUG,
					ProductCategory.CURTAIN,
					ProductCategory.VASE,
					ProductCategory.CUSHION,
					ProductCategory.WALLPAPER,
					ProductCategory.MIRROR);
		}
	},

	BOOKS("Books", "BookOpen", IconCategory.STATIONERY) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.FICTION,
					ProductCategory.NON_FICTION,
					ProductCategory.ACADEMIC,
					ProductCategory.CHILDREN_BOOK,
					ProductCategory.RELIGIOUS_BOOK,
					ProductCategory.NOTEBOOK,
					ProductCategory.STATIONERY);
		}
	},

	SPORTS("Sports", "Dumbbell", IconCategory.ACTIVE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.SPORTS_FOOTWEAR,
					ProductCategory.GYM_EQUIPMENT,
					ProductCategory.SPORTSWEAR,
					ProductCategory.BICYCLE,
					ProductCategory.SPORTS_ACCESSORY,
					ProductCategory.TEAM_SPORT);
		}
	},

	TOYS("Toys", "ToyBrick", IconCategory.LEISURE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.BOARD_GAME,
					ProductCategory.SOFT_TOY,
					ProductCategory.BUILDING_TOY,
					ProductCategory.PUZZLE,
					ProductCategory.REMOTE_CONTROL,
					ProductCategory.EDUCATIONAL_TOY);
		}
	},

	PET_SUPPLIES("Pet Supplies", "PawPrint", IconCategory.LEISURE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.PET_FOOD,
					ProductCategory.PET_TOY,
					ProductCategory.PET_ACCESSORY,
					ProductCategory.PET_CARE,
					ProductCategory.AQUARIUM);
		}
	},

	FLOWERS("Flowers", "Flower", IconCategory.NATURE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.ROSE,
					ProductCategory.ORCHID,
					ProductCategory.TULIP,
					ProductCategory.LILY,
					ProductCategory.BOUQUET,
					ProductCategory.POTTED_FLOWER,
					ProductCategory.FLOWER_DECOR);
		}
	},

	ELECTRONICS("Electronics", "Tv", IconCategory.ELECTRONICS) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.TELEVISION,
					ProductCategory.AUDIO_SYSTEM,
					ProductCategory.CAMERA_ELECTRONIC,
					ProductCategory.GADGET,
					ProductCategory.HOME_APPLIANCE,
					ProductCategory.SMARTHOME);
		}
	},

	GROCERY_BEVERAGES("Beverages", "CupSoda", IconCategory.STORE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.JUICE,
					ProductCategory.SOFT_DRINK,
					ProductCategory.WATER,
					ProductCategory.ENERGY_DRINK,
					ProductCategory.MILK,
					ProductCategory.TEA_BEVERAGE,
					ProductCategory.COFFEE_BEVERAGE);
		}
	},

	BEAUTY_SERVICES("Beauty Services", "Scissors", IconCategory.SERVICE) {
		@Override
		public Set<ProductCategory> categories() {
			return ordered(
					ProductCategory.HAIRCUT,
					ProductCategory.SPA,
					ProductCategory.MASSAGE,
					ProductCategory.NAIL_SALON,
					ProductCategory.SKIN_TREATMENT,
					ProductCategory.BRIDAL_MAKEUP);
		}
	};

	/** Human-readable name shown in the shop-type picker. */
	private final String label;

	/** react-icons name, so the frontend renders the same glyph the API names. */
	private final String icon;

	private final IconCategory iconGroup;

	ShopType(String label, String icon, IconCategory iconGroup) {
		this.label = label;
		this.icon = icon;
		this.iconGroup = iconGroup;
	}

	/**
	 * The product categories a shop of this type may assign. Returned in declaration order so
	 * the dropdown is stable rather than alphabetical.
	 */
	public abstract Set<ProductCategory> categories();

	/** True when this shop type may use the given category. */
	public boolean allows(ProductCategory category) {
		return category != null && categories().contains(category);
	}

	public String label() {
		return label;
	}

	public String icon() {
		return icon;
	}

	public IconCategory iconGroup() {
		return iconGroup;
	}

	/**
	 * Preserves the order the categories were declared in.
	 *
	 * <p>{@link Set#of} would work but gives no ordering guarantee the UI can rely on, and
	 * {@code EnumSet.range} would force an artificial alphabetical order. A {@link
	 * LinkedHashSet} over an explicit list keeps the developer's ordering intentional.
	 */
	private static Set<ProductCategory> ordered(ProductCategory... values) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(values)));
	}

	public static ShopType fromCode(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		try {
			return valueOf(code.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Grouping used to pick an icon family in the frontend, so the picker renders a coherent
	 * set rather than nineteen unrelated glyphs.
	 */
	public enum IconCategory {
		CARE, APPAREL, STORE, ELECTRONICS, NATURE, ACCESSORY, HOME, STATIONERY, ACTIVE, LEISURE, SERVICE
	}
}
