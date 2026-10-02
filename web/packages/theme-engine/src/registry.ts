import type { SectionType, ThemeSection } from './schema';
import { SECTION_TYPES } from './schema';

/**
 * The closed section registry (D8).
 *
 * Every section type in the schema must have exactly one component here. The registry is
 * checked against the schema at module load in development, so adding a type to
 * `SECTION_TYPES` without writing its component fails immediately rather than rendering
 * `undefined` for a customer.
 */

export interface SectionDefinition {
  type: SectionType;
  /** Shown in the editor's "add section" list. */
  label: string;
  description: string;
  /** Groups the editor palette. */
  group: 'Hero' | 'Content' | 'Commerce' | 'Social proof' | 'Marketing' | 'Layout';
  /** Default props when the section is first added. */
  defaultProps: Record<string, unknown>;
  /** Default variant, if the type has render variants. */
  defaultVariant?: string;
  /** True once the section has been implemented; hides stubs from the palette. */
  implemented: boolean;
}

export const SECTION_DEFINITIONS: Record<SectionType, SectionDefinition> = {
  'announcement-bar': {
    type: 'announcement-bar',
    label: 'Announcement Bar',
    description: 'A thin bar above the header for shipping offers or notices.',
    group: 'Marketing',
    defaultProps: { text: 'Free delivery over Rs. 2000', dismissible: true, link: '' },
    implemented: true,
  },
  hero: {
    type: 'hero',
    label: 'Hero',
    description: 'The large banner at the top of the homepage.',
    group: 'Hero',
    defaultProps: {
      heading: 'Your headline here',
      subheading: 'One sentence about what you sell.',
      imageUrl: '',
      cta: { label: 'Shop now', href: '#' },
    },
    defaultVariant: 'split-image',
    implemented: true,
  },
  'category-grid': {
    type: 'category-grid',
    label: 'Category Grid',
    description: 'Tiles linking to each category. Resolved from the shop type automatically.',
    group: 'Commerce',
    defaultProps: { columns: 4, source: 'shop_categories', limit: 8 },
    implemented: true,
  },
  'product-carousel': {
    type: 'product-carousel',
    label: 'Product Carousel',
    description: 'A horizontally scrolling row of products.',
    group: 'Commerce',
    defaultProps: { source: 'featured', limit: 12, layoutId: 'card-soft' },
    implemented: true,
  },
  'product-grid': {
    type: 'product-grid',
    label: 'Product Grid',
    description: 'A grid of products, newest first or by category.',
    group: 'Commerce',
    defaultProps: { source: 'latest', limit: 8, columns: 4 },
    implemented: true,
  },
  'collection-list': {
    type: 'collection-list',
    label: 'Collection List',
    description: 'Editorial groupings of products.',
    group: 'Commerce',
    defaultProps: { source: 'manual', collections: [] },
    implemented: true,
  },
  'promo-banner': {
    type: 'promo-banner',
    label: 'Promo Banner',
    description: 'A full-width image with a link, for sales and campaigns.',
    group: 'Marketing',
    defaultProps: { imageUrl: '', href: '#', overlay: 0.4, heading: '' },
    implemented: true,
  },
  'image-with-text': {
    type: 'image-with-text',
    label: 'Image With Text',
    description: 'An image beside a paragraph, for telling your story.',
    group: 'Content',
    defaultProps: { imageUrl: '', heading: '', body: '', imagePosition: 'left' },
    implemented: true,
  },
  'rich-text': {
    type: 'rich-text',
    label: 'Rich Text',
    description: 'A formatted block of your own copy.',
    group: 'Content',
    defaultProps: { body: '' },
    implemented: true,
  },
  video: {
    type: 'video',
    label: 'Video',
    description: 'An embedded or self-hosted video.',
    group: 'Content',
    defaultProps: { url: '', posterUrl: '', autoplay: false },
    implemented: true,
  },
  gallery: {
    type: 'gallery',
    label: 'Gallery',
    description: 'A grid of images from your media library.',
    group: 'Content',
    defaultProps: { mediaIds: [], columns: 3 },
    implemented: true,
  },
  testimonials: {
    type: 'testimonials',
    label: 'Testimonials',
    description: 'Customer quotes. Reads from real reviews once they exist.',
    group: 'Social proof',
    defaultProps: { source: 'reviews', limit: 6 },
    implemented: true,
  },
  faq: {
    type: 'faq',
    label: 'FAQ',
    description: 'Expandable questions and answers.',
    group: 'Content',
    defaultProps: {
      items: [
        { q: 'Do you deliver outside Kathmandu?', a: 'Yes, across Nepal.' },
      ],
    },
    implemented: true,
  },
  newsletter: {
    type: 'newsletter',
    label: 'Newsletter',
    description: 'An email signup form.',
    group: 'Marketing',
    defaultProps: { heading: 'Join our list', subheading: 'New arrivals, once a month.' },
    implemented: true,
  },
  countdown: {
    type: 'countdown',
    label: 'Countdown',
    description: 'A timer counting to a sale deadline.',
    group: 'Marketing',
    defaultProps: { targetIso: '', heading: 'Sale ends in' },
    implemented: true,
  },
  'trust-badges': {
    type: 'trust-badges',
    label: 'Trust Badges',
    description: 'Short reassurances such as cash on delivery or easy returns.',
    group: 'Social proof',
    defaultProps: { items: ['Cash on delivery', '7-day returns', 'Secure checkout'] },
    implemented: true,
  },
  'feature-list': {
    type: 'feature-list',
    label: 'Feature List',
    description: 'A short list of what makes your shop worth choosing.',
    group: 'Content',
    defaultProps: { items: ['Free local delivery', 'Handmade in Nepal'] },
    implemented: true,
  },
  'cta-banner': {
    type: 'cta-banner',
    label: 'Call To Action',
    description: 'A prominent block with one button.',
    group: 'Marketing',
    defaultProps: { heading: '', body: '', cta: { label: 'Get started', href: '#' } },
    implemented: true,
  },
  map: {
    type: 'map',
    label: 'Map',
    description: 'Your shop location.',
    group: 'Content',
    defaultProps: { query: '', zoom: 13 },
    implemented: true,
  },
  'social-feed': {
    type: 'social-feed',
    label: 'Social Feed',
    description: 'Your latest Instagram or Facebook posts.',
    group: 'Social proof',
    defaultProps: { platform: 'instagram', limit: 8 },
    implemented: false,
  },
  divider: {
    type: 'divider',
    label: 'Divider',
    description: 'A horizontal rule.',
    group: 'Layout',
    defaultProps: { spacing: 'md' },
    implemented: true,
  },
  spacer: {
    type: 'spacer',
    label: 'Spacer',
    description: 'Empty vertical space.',
    group: 'Layout',
    defaultProps: { height: 48 },
    implemented: true,
  },
  html: {
    type: 'html',
    label: 'Custom HTML',
    description: 'Raw HTML. Sanitised on render; no scripts, styles or iframes.',
    group: 'Content',
    defaultProps: { html: '' },
    implemented: true,
  },
  'unknown-section': {
    type: 'unknown-section',
    label: 'Unknown Section',
    description: 'Placeholder shown when a theme references a section this build does not have.',
    group: 'Layout',
    defaultProps: {},
    implemented: true,
  },
};

// Fail fast in development if the schema and registry drift apart.
if (process.env.NODE_ENV !== 'production') {
  const schemaTypes = new Set<string>(SECTION_TYPES);
  const registryTypes = new Set<string>(Object.keys(SECTION_DEFINITIONS));
  const missing = [...schemaTypes].filter((t) => !registryTypes.has(t));
  const extra = [...registryTypes].filter((t) => !schemaTypes.has(t));
  if (missing.length || extra.length) {
    throw new Error(
      `Section registry out of sync with the schema. ` +
        `Missing components: [${missing.join(', ')}]. ` +
        `Not in the schema: [${extra.join(', ')}].`,
    );
  }
}

export function sectionDefinition(type: SectionType): SectionDefinition {
  return SECTION_DEFINITIONS[type];
}

/** Sections offered in the editor palette, grouped for the add-section dialog. */
export function paletteGroups(): Array<{
  group: SectionDefinition['group'];
  sections: SectionDefinition[];
}> {
  const byGroup = new Map<SectionDefinition['group'], SectionDefinition[]>();
  for (const def of Object.values(SECTION_DEFINITIONS)) {
    if (!def.implemented) continue;
    const list = byGroup.get(def.group) ?? [];
    list.push(def);
    byGroup.set(def.group, list);
  }
  return [...byGroup.entries()].map(([group, sections]) => ({ group, sections }));
}

/** Builds a new section of the given type with its declared defaults. */
export function createSection(type: SectionType, id?: string): ThemeSection {
  const def = SECTION_DEFINITIONS[type];
  return {
    id: id ?? `${type}-${Math.random().toString(36).slice(2, 8)}`,
    type,
    ...(def.defaultVariant ? { variant: def.defaultVariant } : {}),
    props: structuredClone(def.defaultProps),
  };
}
