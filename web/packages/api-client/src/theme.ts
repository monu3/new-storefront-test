import { api } from './client';
import type { ShopTypeCode } from '@store-app/enums';

/**
 * Theme endpoints (D8).
 *
 * <p>The theme document crosses the wire as an opaque JSON **string**, not a typed object, and
 * that is deliberate. Its shape belongs to the shared zod schema and it changes with the section
 * set; binding it into this package would make every schema change a coordinated edit here too.
 * The console parses and validates it with the same schema the storefront uses, so a theme the
 * editor accepts is a theme the renderer accepts.
 */

export type ThemePriceTier = 'FREE' | 'SUBSCRIBED';
export type ThemeAssetKind =
  | 'HERO'
  | 'HEADER_PAGE'
  | 'FOOTER'
  | 'BANNER'
  | 'MEDIA_PAGE'
  | 'CATALOG_LAYOUT'
  | 'PRODUCT_LAYOUT'
  | 'POLICY_PAGE';

export interface ThemeView {
  id: string;
  name: string;
  published: boolean;
  publishedAt: string | null;
  templateId: string | null;
  schemaVersion: number;
  updatedAt: string;
}

export interface ThemeDetail {
  theme: ThemeView;
  /** The theme document, as stored. */
  config: string;
  /**
   * Non-fatal problems — a section referencing an asset the shop has not uploaded yet.
   * Warnings rather than errors, because a shop can lay out a page before it has the photograph.
   */
  warnings: string[];
}

export interface ThemeListResponse {
  /** The live theme, or null when the storefront is showing the platform default. */
  published: ThemeView | null;
  themes: ThemeView[];
  hasPublishedTheme: boolean;
}

export interface MarketplaceEntry {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  shopType: ShopTypeCode | null;
  priceTier: ThemePriceTier;
  previewImageUrl: string | null;
  sectionCount: number;
}

export interface TemplateView {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  shopType: ShopTypeCode | null;
  priceTier: ThemePriceTier;
  active: boolean;
  published: boolean;
  sortOrder: number;
  previewImageUrl: string | null;
  publishedAt: string | null;
}

export interface AssetView {
  id: string;
  kind: ThemeAssetKind;
  handle: string;
  name: string;
  config: string;
  updatedAt: string;
}

export interface PreviewResponse {
  config: string;
  sectionCount: number;
  /** False when the storefront is falling back to the platform default. */
  fromPublishedTheme: boolean;
  shopType: string;
  /** The platform default, so the editor can offer "start from this" and show what it is. */
  platformDefault: string;
}

export const theme = {
  list: () => api.get<ThemeListResponse>('/api/v1/shop/theme'),
  get: (themeId: string) => api.get<ThemeDetail>(`/api/v1/shop/theme/${themeId}`),

  /** Saving is refused while the theme is live; unpublish first. */
  save: (themeId: string, config: string, name?: string) =>
    api.patch<ThemeDetail>(`/api/v1/shop/theme/${themeId}`, { config, name }),

  publish: (themeId: string) =>
    api.post<ThemeView>(`/api/v1/shop/theme/${themeId}/publish`, {}),
  unpublish: (themeId: string) =>
    api.post<ThemeView>(`/api/v1/shop/theme/${themeId}/unpublish`, {}),
  remove: (themeId: string) => api.delete<void>(`/api/v1/shop/theme/${themeId}`),

  marketplace: () => api.get<MarketplaceEntry[]>('/api/v1/shop/theme/marketplace'),
  install: (templateId: string) =>
    api.post<ThemeView>(`/api/v1/shop/theme/install/${templateId}`, {}),
  resetToTemplate: (themeId: string, templateId: string) =>
    api.post<ThemeDetail>(`/api/v1/shop/theme/${themeId}/reset/${templateId}`, {}),

  preview: () => api.get<PreviewResponse>('/api/v1/shop/theme/preview'),

  assets: (kind?: ThemeAssetKind) =>
    api.get<AssetView[]>(
      `/api/v1/shop/theme/assets${kind ? `?kind=${encodeURIComponent(kind)}` : ''}`,
    ),
  putAsset: (asset: { kind: ThemeAssetKind; handle: string; name: string; config: string }) =>
    api.put<AssetView>('/api/v1/shop/theme/assets', asset),
  deleteAsset: (assetId: string) => api.delete<void>(`/api/v1/shop/theme/assets/${assetId}`),
};

export const adminThemes = {
  list: () => api.get<TemplateView[]>('/api/v1/admin/theme-templates'),
  get: (templateId: string) =>
    api.get<{ template: TemplateView; config: string; sectionCount: number }>(
      `/api/v1/admin/theme-templates/${templateId}`,
    ),
  create: (input: {
    code: string;
    name: string;
    description?: string;
    shopType?: ShopTypeCode | null;
    config: string;
    priceTier?: ThemePriceTier;
  }) =>
    api.post<{ template: TemplateView; config: string; sectionCount: number }>(
      '/api/v1/admin/theme-templates',
      input,
    ),
  update: (
    templateId: string,
    input: {
      name?: string;
      description?: string;
      shopType?: ShopTypeCode | null;
      config?: string;
      sortOrder?: number;
      previewMediaId?: string;
    },
  ) =>
    api.patch<{ template: TemplateView; config: string; sectionCount: number }>(
      `/api/v1/admin/theme-templates/${templateId}`,
      input,
    ),
  publish: (templateId: string) =>
    api.post<TemplateView>(`/api/v1/admin/theme-templates/${templateId}/publish`, {}),
  unpublish: (templateId: string) =>
    api.post<TemplateView>(`/api/v1/admin/theme-templates/${templateId}/unpublish`, {}),
};
