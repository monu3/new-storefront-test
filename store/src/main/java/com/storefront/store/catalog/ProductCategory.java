package com.storefront.store.catalog;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Every product category the platform understands (plan decision D18).
 *
 * <p>One flat enum rather than a physically nested {@code ShopType.PHARMACY.ProductCategory}.
 * Nesting would make the type awkward to import, impossible to iterate cleanly, and a nuisance
 * for the API layer. The guarantee the nesting was meant to provide is instead expressed by
 * {@link ShopType#categories()}, which sits directly beside the shop type it belongs to, so the
 * allowed set is still declared in exactly one place.
 *
 * <p>Membership is defined by the shop types, never by this class. A value that no shop type
 * claims is a bug, and {@code CategoryEnumIntegrityTest} fails the build if that ever happens.
 */
public enum ProductCategory {

	// ---- Pharmacy ----
	MEDICINE("Medicine"),
	MEDICAL_DEVICE("Medical Device"),
	SUPPLEMENT("Supplement"),
	PERSONAL_CARE("Personal Care"),
	BABY_CARE("Baby Care"),

	// ---- Fashion ----
	MENS_CLOTHING("Men's Clothing"),
	WOMENS_CLOTHING("Women's Clothing"),
	KIDS_CLOTHING("Kids' Clothing"),
	FOOTWEAR("Footwear"),
	ACCESSORIES("Accessories"),
	BAGS("Bags"),
	JEWELRY("Jewelry"),
	WATCHES("Watches"),
	HANDBAG("Handbag"),

	// ---- Grocery ----
	RICE("Rice"),
	LENTIL("Lentil"),
	SPICES("Spices"),
	OIL("Oil"),
	SNACKS("Snacks"),
	DAIRY("Dairy"),
	TEA("Tea"),
	COFFEE("Coffee"),
	SUGAR_SALT("Sugar & Salt"),
	MASALA("Masala"),

	// ---- Tech ----
	LAPTOP("Laptop"),
	DESKTOP("Desktop"),
	TECH_ACCESSORY("Tech Accessory"),
	PERIPHERAL("Peripheral"),
	CAMERA("Camera"),
	AUDIO("Audio"),
	STORAGE("Storage"),
	NETWORKING("Networking"),

	// ---- Plant shop ----
	INDOOR_PLANT("Indoor Plant"),
	OUTDOOR_PLANT("Outdoor Plant"),
	SEED("Seed"),
	SOIL("Soil"),
	FERTILIZER("Fertilizer"),
	PLANTER("Planter"),
	POTTING_MIX("Potting Mix"),

	// ---- Baby shop ----
	DIAPER("Diaper"),
	BABY_FOOD("Baby Food"),
	BABY_CLOTHING("Baby Clothing"),
	STROLLER("Stroller"),
	BABY_TOY("Baby Toy"),
	BABY_FURNITURE("Baby Furniture"),

	// ---- Mobile shop ----
	SMARTPHONE("Smartphone"),
	FEATURE_PHONE("Feature Phone"),
	TABLET("Tablet"),
	SMARTWATCH("Smartwatch"),
	MOBILE_ACCESSORY("Mobile Accessory"),
	SIM_CARD("SIM Card"),
	EARPHONE("Earphone"),

	// ---- Cosmetics ----
	SKINCARE("Skincare"),
	HAIRCARE("Haircare"),
	MAKEUP("Makeup"),
	FRAGRANCE("Fragrance"),
	NAIL_CARE("Nail Care"),
	SUNSCREEN("Sunscreen"),

	// ---- Jewellery ----
	RING("Ring"),
	NECKLACE("Necklace"),
	EARRING("Earring"),
	BRACELET("Bracelet"),
	BANGLES("Bangles"),
	WEDDING_SET("Wedding Set"),
	SILVER_JEWELRY("Silver Jewelry"),
	GOLD_JEWELRY("Gold Jewelry"),

	// ---- Furniture ----
	SOFA("Sofa"),
	BED("Bed"),
	TABLE("Table"),
	CHAIR("Chair"),
	WARDROBE("Wardrobe"),
	MATTRESS("Mattress"),
	TV_UNIT("TV Unit"),
	OFFICE_DESK("Office Desk"),

	// ---- Home decor ----
	WALL_DECOR("Wall Decor"),
	LIGHTING("Lighting"),
	RUG("Rug"),
	CURTAIN("Curtain"),
	VASE("Vase"),
	CUSHION("Cushion"),
	WALLPAPER("Wallpaper"),
	MIRROR("Mirror"),

	// ---- Books ----
	FICTION("Fiction"),
	NON_FICTION("Non-Fiction"),
	ACADEMIC("Academic"),
	CHILDREN_BOOK("Children's Book"),
	RELIGIOUS_BOOK("Religious Book"),
	NOTEBOOK("Notebook"),
	STATIONERY("Stationery"),

	// ---- Sports ----
	SPORTS_FOOTWEAR("Sports Footwear"),
	GYM_EQUIPMENT("Gym Equipment"),
	SPORTSWEAR("Sportswear"),
	BICYCLE("Bicycle"),
	SPORTS_ACCESSORY("Sports Accessory"),
	TEAM_SPORT("Team Sport"),

	// ---- Toys ----
	BOARD_GAME("Board Game"),
	SOFT_TOY("Soft Toy"),
	BUILDING_TOY("Building Toy"),
	PUZZLE("Puzzle"),
	REMOTE_CONTROL("Remote Control"),
	EDUCATIONAL_TOY("Educational Toy"),

	// ---- Pet supplies ----
	PET_FOOD("Pet Food"),
	PET_TOY("Pet Toy"),
	PET_ACCESSORY("Pet Accessory"),
	PET_CARE("Pet Care"),
	AQUARIUM("Aquarium"),

	// ---- Flowers ----
	ROSE("Rose"),
	ORCHID("Orchid"),
	TULIP("Tulip"),
	LILY("Lily"),
	BOUQUET("Bouquet"),
	POTTED_FLOWER("Potted Flower"),
	FLOWER_DECOR("Flower Decor"),

	// ---- Electronics ----
	TELEVISION("Television"),
	AUDIO_SYSTEM("Audio System"),
	CAMERA_ELECTRONIC("Electronic Camera"),
	GADGET("Gadget"),
	HOME_APPLIANCE("Home Appliance"),
	SMARTHOME("Smart Home"),

	// ---- Beverages ----
	JUICE("Juice"),
	SOFT_DRINK("Soft Drink"),
	WATER("Water"),
	ENERGY_DRINK("Energy Drink"),
	MILK("Milk"),
	TEA_BEVERAGE("Tea Beverage"),
	COFFEE_BEVERAGE("Coffee Beverage"),

	// ---- Beauty services ----
	HAIRCUT("Haircut"),
	SPA("Spa"),
	MASSAGE("Massage"),
	NAIL_SALON("Nail Salon"),
	SKIN_TREATMENT("Skin Treatment"),
	BRIDAL_MAKEUP("Bridal Makeup");

	private final String label;

	ProductCategory(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/**
	 * URL-safe handle for storefront category routes, e.g. {@code /s/acme/c/medicine}.
	 *
	 * <p>Derived rather than stored: a handle is a presentation concern, and keeping it derived
	 * means renaming a label can never orphan a bookmarked URL.
	 */
	public String slug() {
		return name().toLowerCase().replace('_', '-');
	}

	public static ProductCategory fromCode(String code) {
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
	 * Every category claimed by at least one shop type. Computed rather than hand-maintained, so
	 * a category cannot be "forgotten" here and appear valid when it is not.
	 */
	public static Set<ProductCategory> allClaimed() {
		Set<ProductCategory> claimed = new HashSet<>();
		for (ShopType type : ShopType.values()) {
			claimed.addAll(type.categories());
		}
		return Collections.unmodifiableSet(claimed);
	}

	/** Categories not claimed by any shop type. Should always be empty. */
	public static Set<ProductCategory> unclaimed() {
		Set<ProductCategory> unclaimed = new HashSet<>(EnumSet.allOf(ProductCategory.class));
		unclaimed.removeAll(allClaimed());
		return Collections.unmodifiableSet(unclaimed);
	}
}
