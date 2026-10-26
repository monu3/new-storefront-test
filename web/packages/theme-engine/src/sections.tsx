/**
 * The section components (D8).
 *
 * Every one is a **server component**: no 'use client', no hooks, no effects. That is what makes
 * the storefront indexable and share-previewable, which the WhatsApp and social selling flow
 * depends on — a page that hydrates before its content exists cannot be crawled.
 *
 * Each component receives props it can rely on because the resolver already ran: `products` is
 * filled for a commerce section, `categories` for a grid, `asset` for anything referencing one.
 * A component therefore never fetches, and the same code renders the editor preview and the live
 * storefront — which is what "visually identical" means concretely.
 */

import type { ReactNode } from 'react';

import { formatPrice, sectionImageUrl } from './resolve';
import { SafeImage } from './safe-image';
import type { ProductSummary, ResolvedSection } from './resolve';
import type { SectionType } from './schema';

/** Props every section receives. */
export interface SectionProps {
  resolved: ResolvedSection;
}

function text(props: Record<string, unknown>, key: string, fallback = ''): string {
  const value = props[key];
  return typeof value === 'string' ? value : fallback;
}

function num(props: Record<string, unknown>, key: string, fallback: number): number {
  const value = props[key];
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function SafeHref({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  // The server already refuses a javascript: or protocol-relative href on write. This is the
  // renderer's own belt-and-braces, because a theme can also arrive from a template authored
  // before that rule existed.
  const safe = href.startsWith('/') && !href.startsWith('//')
    ? href
    : href.startsWith('https://') || href.startsWith('#')
      ? href
      : '#';
  return (
    <a href={safe} className={className}>
      {children}
    </a>
  );
}

// ---------------------------------------------------------------------------
// Commerce
// ---------------------------------------------------------------------------

/**
 * One product tile.
 *
 * <p>Wrapped in a link to the product page. It was not, and that made the storefront read-only: a
 * customer could see a well-formed grid of products and had no way to reach any of them. The theme
 * engine's own links (`/c/...`) worked because they were written as anchors; these were built as
 * plain divs, which is the kind of omission that only shows up when someone tries to buy something.
 *
 * <p>The whole card is one link rather than a link around the image and another around the title.
 * Two targets for one product is a decision the customer has to make, and they get it wrong
 * roughly half the time.
 *
 * <p>Anchored with {@link SafeHref}, not a framework `<Link>`. This package is shared by the
 * Next.js storefront and the Vite console's editor preview, so it cannot import either app's router
 * — a rule the plan enforces with an ESLint boundary. A plain anchor works in both and keeps the
 * preview's markup identical to the live page's.
 */
function ProductCard({
  product,
  card = 'bordered',
}: {
  product: ProductSummary;
  /** The theme's card treatment. `bordered` is the original tile. */
  card?: 'bordered' | 'plain' | 'editorial';
}) {
  const discounted = product.hasDiscount || (product.compareAt ?? 0) > product.price;
  const href = `/s/${product.shopSlug ?? ''}/p/${product.slug}`;
  const unavailable = product.purchasable === false;

  const price = (
    <div className="flex items-baseline gap-2">
      <span className="text-sm font-semibold">{formatPrice(product.price)}</span>
      {discounted && product.compareAt ? (
        <span className="text-xs text-muted-foreground line-through">
          {formatPrice(product.compareAt)}
        </span>
      ) : null}
    </div>
  );

  if (card !== 'bordered') {
    // Borderless and editorial cards carry no chrome at all: the photograph is the object, and a
    // border around every product makes a fashion shop look like a spreadsheet.
    const editorial = card === 'editorial';
    return (
      <SafeHref
        href={href}
        className={`group flex flex-col gap-3 text-card-foreground ${
          unavailable ? 'opacity-60' : ''
        }`}
      >
        <div
          className={`${
            editorial ? 'aspect-[3/4]' : 'aspect-square'
          } w-full overflow-hidden rounded-[var(--radius)] bg-muted`}
        >
          <SafeImage
            src={product.imageUrl}
            alt={product.name}
            loading="lazy"
            className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-[1.04]"
          />
        </div>
        <div
          className={
            editorial
              ? 'flex items-start justify-between gap-4 border-b border-border pb-3'
              : 'flex items-start justify-between gap-3'
          }
        >
          <div className="min-w-0">
            {product.categoryLabel ? (
              <p className="text-[11px] uppercase tracking-[0.12em] text-muted-foreground">
                {product.categoryLabel}
              </p>
            ) : null}
            <h3
              className={`mt-1 line-clamp-2 leading-snug ${
                editorial ? 'text-base font-normal' : 'text-sm font-medium'
              }`}
            >
              {product.name}
            </h3>
            {unavailable ? (
              <p className="mt-1 text-xs text-muted-foreground">Out of stock</p>
            ) : null}
          </div>
          <div className="shrink-0 pt-4">{price}</div>
        </div>
      </SafeHref>
    );
  }

  return (
    <SafeHref
      href={href}
      className={`group flex flex-col overflow-hidden rounded-2xl border border-border bg-card text-card-foreground transition-shadow hover:shadow-md ${
        unavailable ? 'opacity-60' : ''
      }`}
    >
      <div className="aspect-square w-full overflow-hidden bg-muted">
        <SafeImage
          src={product.imageUrl}
          alt={product.name}
          loading="lazy"
          className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
        />
      </div>
      <div className="flex flex-1 flex-col gap-1 p-4">
        {product.categoryLabel ? (
          <span className="text-xs text-muted-foreground">{product.categoryLabel}</span>
        ) : null}
        <h3 className="line-clamp-2 text-sm font-medium leading-snug">{product.name}</h3>
        <div className="mt-auto pt-2">{price}</div>
        {unavailable ? (
          <span className="text-xs text-muted-foreground">Out of stock</span>
        ) : null}
      </div>
    </SafeHref>
  );
}

function ProductGrid({ resolved }: SectionProps) {
  const { section, products, layout } = resolved;
  const columns = Math.min(Math.max(num(section.props, 'columns', 4), 1), 6);

  if (products.length === 0) {
    return <EmptySection label="No products to show yet" />;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <div
        className="grid gap-4"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {products.map((p) => (
          <ProductCard key={p.id ?? p.name} product={p} card={layout.card} />
        ))}
      </div>
    </Section>
  );
}

function ProductCarousel({ resolved }: SectionProps) {
  const { section, products, layout } = resolved;

  if (products.length === 0) {
    return <EmptySection label="No products to show yet" />;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      {/* A scroll-snap row rather than a carousel with JS. The content is in the HTML either
          way, and a customer's swipe is a scroll the browser already handles. */}
      <div className="flex snap-x snap-mandatory gap-4 overflow-x-auto pb-2">
        {products.map((p) => (
          <div key={p.id ?? p.name} className="w-56 shrink-0 snap-start">
            <ProductCard product={p} card={layout.card} />
          </div>
        ))}
      </div>
    </Section>
  );
}

function CategoryGrid({ resolved }: SectionProps) {
  const { section, categories, shop, layout } = resolved;
  const columns = Math.min(Math.max(num(section.props, 'columns', 4), 2), 6);

  if (categories.length === 0) {
    return <EmptySection label="No categories yet" />;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <div
        className="grid gap-3"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {categories.map((c) => (
          <SafeHref
            key={c.code}
            href={`/s/${shop.slug}/c/${c.code.toLowerCase()}`}
            className={
              layout.card === 'bordered'
                ? 'flex flex-col items-center justify-center gap-1 rounded-lg border bg-card p-6 text-center transition-colors hover:bg-muted'
                : 'flex flex-col items-center justify-center gap-1 border-b border-border py-6 text-center transition-colors hover:text-primary'
            }
          >
            <span className="text-sm font-medium">{c.label}</span>
            <span className="text-xs text-muted-foreground">{c.productCount} products</span>
          </SafeHref>
        ))}
      </div>
    </Section>
  );
}

function CollectionList({ resolved }: SectionProps) {
  const { section } = resolved;
  const collections = Array.isArray(section.props?.collections) ? section.props.collections : [];

  if (collections.length === 0) {
    return <EmptySection label="No collections yet" />;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading')} />
      <ul className="grid gap-3 sm:grid-cols-2">
        {collections.map((c: Record<string, unknown>) => (
          <li key={text(c, 'title')} className="rounded-lg border bg-card p-4">
            <SafeHref href={text(c, 'href', '#')} className="font-medium">
              {text(c, 'title')}
            </SafeHref>
            {text(c, 'description') ? (
              <p className="mt-1 text-sm text-muted-foreground">{text(c, 'description')}</p>
            ) : null}
          </li>
        ))}
      </ul>
    </Section>
  );
}

// ---------------------------------------------------------------------------
// Hero and marketing
// ---------------------------------------------------------------------------

function Hero({ resolved }: SectionProps) {
  const { section, shop, layout } = resolved;
  const image = sectionImageUrl(resolved);
  const cta = (section.props?.cta ?? {}) as Record<string, unknown>;
  const variant = section.variant ?? 'split-image';
  const display = layout.display;

  const headingClass =
    display === 'editorial'
      ? 'text-5xl font-medium leading-[0.98] tracking-[-0.03em] sm:text-6xl lg:text-7xl'
      : display === 'compact'
        ? 'text-3xl font-semibold uppercase leading-[1.06] tracking-[0.01em] sm:text-4xl'
        : 'text-4xl font-semibold leading-[1.04] tracking-[-0.035em] sm:text-5xl';

  const ctaClass =
    'mt-7 inline-flex h-12 items-center rounded-[var(--radius)] bg-primary px-7 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90';

  const copy = (centred: boolean) => (
    <div className={centred ? 'mx-auto max-w-2xl text-center' : ''}>
      <h1 className={headingClass}>{text(section.props, 'heading', shop.name)}</h1>
      {text(section.props, 'subheading') ? (
        <p className="mt-4 text-base text-muted-foreground">{text(section.props, 'subheading')}</p>
      ) : null}
      {text(cta, 'label') ? (
        <SafeHref href={text(cta, 'href', '#products')} className={ctaClass}>
          {text(cta, 'label')}
        </SafeHref>
      ) : null}
    </div>
  );

  /**
   * A typographic panel.
   *
   * <p>Most shops never upload a hero photograph, and a 4:3 placeholder is the largest object on
   * the page — a designed homepage with no product photography should still look designed. The
   * panel is also the honest fallback for an `overlay` hero whose image is missing: a solid block
   * with readable copy, rather than white text on a grey rectangle.
   */
  const panel = (
    <section className="my-6 overflow-hidden rounded-[var(--radius)] bg-primary px-6 py-16 text-primary-foreground sm:px-12 sm:py-24">
      <div className="max-w-3xl">
        <h1 className={headingClass}>{text(section.props, 'heading', shop.name)}</h1>
        {text(section.props, 'subheading') ? (
          <p className="mt-4 max-w-xl text-base text-primary-foreground/80">
            {text(section.props, 'subheading')}
          </p>
        ) : null}
        {text(cta, 'label') ? (
          <SafeHref
            href={text(cta, 'href', '#products')}
            className="mt-8 inline-flex h-12 items-center rounded-[var(--radius)] bg-primary-foreground px-7 text-sm font-medium text-primary transition-opacity hover:opacity-90"
          >
            {text(cta, 'label')}
          </SafeHref>
        ) : null}
      </div>
    </section>
  );

  if (variant === 'panel') {
    return panel;
  }

  if (variant === 'overlay') {
    if (!image) {
      return panel;
    }
    return (
      <section className="relative my-6 overflow-hidden rounded-[var(--radius)]">
        <SafeImage
          src={image}
          alt=""
          loading="eager"
          className="h-[440px] w-full object-cover sm:h-[520px]"
        />
        <div
          className="absolute inset-0 bg-gradient-to-t from-black/75 via-black/20 to-transparent"
          aria-hidden
        />
        <div className="absolute inset-x-0 bottom-0 p-6 sm:p-10">
          <div className="max-w-2xl text-white">
            <h1 className={headingClass}>{text(section.props, 'heading', shop.name)}</h1>
            {text(section.props, 'subheading') ? (
              <p className="mt-4 text-base text-white/80">
                {text(section.props, 'subheading')}
              </p>
            ) : null}
            {text(cta, 'label') ? (
              <SafeHref
                href={text(cta, 'href', '#products')}
                className="mt-7 inline-flex h-12 items-center rounded-[var(--radius)] bg-white px-7 text-sm font-medium text-black transition-opacity hover:opacity-90"
              >
                {text(cta, 'label')}
              </SafeHref>
            ) : null}
          </div>
        </div>
      </section>
    );
  }

  const centred = variant === 'centered';

  /*
    No photograph is not a hole to fill.

    <p>A split hero without an image used to render a 4:3 grey rectangle opposite the copy: the
    largest object on the page, and the first thing a customer saw. Most shops have not uploaded a
    hero photograph, so that rectangle was the default storefront. Without an image the hero is now
    a typographic one — left-aligned for a split, centred for a centred hero — which is a layout
    plenty of modern shops use deliberately, and which leaves the photograph as an upgrade rather
    than a missing piece.
  */
  if (!image) {
    return (
      <section
        className={centred ? 'py-16 sm:py-24' : 'py-14 sm:py-20'}
        id={text(section.props, 'anchor', 'hero')}
      >
        <div className={centred ? 'mx-auto max-w-3xl' : 'max-w-3xl'}>{copy(centred)}</div>
      </section>
    );
  }

  const picture = (
    <SafeImage
      src={image}
      alt=""
      loading="eager"
      className="aspect-[4/3] w-full rounded-[var(--radius)] object-cover"
      /*
        A monogram plate rather than the words "Add a hero image". That copy was written for the
        owner and was being shown to customers; a large grey rectangle with an instruction in it
        is also the most dominant object on a page that has no photograph. A tinted plate with the
        shop's initials reads as a deliberate brand mark instead of a missing file.
      */
      fallbackClassName="flex aspect-[4/3] w-full items-center justify-center rounded-[var(--radius)] border border-border/70 bg-muted text-6xl font-medium uppercase tracking-[0.24em] text-primary/60"
      fallbackLabel={shop.name.slice(0, 2).toUpperCase()}
    />
  );

  return (
    <section className="pt-6" id={text(section.props, 'anchor', 'hero')}>
      {centred ? (
        <div className="flex flex-col gap-8">
          {copy(true)}
          {picture}
        </div>
      ) : (
        <div className="grid items-center gap-8 md:grid-cols-2">
          {copy(false)}
          {picture}
        </div>
      )}
    </section>
  );
}

function AnnouncementBar({ resolved }: SectionProps) {
  const { section } = resolved;
  const link = text(section.props, 'link');

  if (!text(section.props, 'text')) {
    return null;
  }

  const body = (
    <div className="flex min-h-9 items-center justify-center px-4 py-2 text-center text-xs font-medium">
      {text(section.props, 'text')}
    </div>
  );

  return (
    <div className="bg-primary text-primary-foreground">
      {link ? <SafeHref href={link}>{body}</SafeHref> : body}
    </div>
  );
}

function PromoBanner({ resolved }: SectionProps) {
  const { section } = resolved;
  const image = sectionImageUrl(resolved);
  const overlay = Math.min(Math.max(num(section.props, 'overlay', 0.4), 0), 0.9);

  return (
    <section className="relative overflow-hidden rounded-[1.75rem]">
      <SafeImage
        src={image}
        alt=""
        loading="lazy"
        className="h-56 w-full object-cover sm:h-72"
        fallbackClassName="h-56 w-full bg-muted sm:h-72"
        fallbackLabel=""
      />
      {overlay > 0 ? (
        <div className="absolute inset-0 bg-black" style={{ opacity: overlay }} aria-hidden />
      ) : null}
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 p-6 text-center text-white">
        {text(section.props, 'heading') ? (
          <h2 className="text-2xl font-bold">{text(section.props, 'heading')}</h2>
        ) : null}
        <SafeHref
          href={text(section.props, 'href', '#')}
          className="rounded-md bg-white px-5 py-2 text-sm font-medium text-black"
        >
          {text(section.props, 'ctaLabel', 'Shop now')}
        </SafeHref>
      </div>
    </section>
  );
}

function CtaBanner({ resolved }: SectionProps) {
  const { section } = resolved;
  const cta = (section.props?.cta ?? {}) as Record<string, unknown>;

  return (
    <section className="rounded-lg border bg-muted/50 px-6 py-10 text-center">
      {text(section.props, 'heading') ? (
        <h2 className="text-2xl font-bold">{text(section.props, 'heading')}</h2>
      ) : null}
      {text(section.props, 'body') ? (
        <p className="mx-auto mt-2 max-w-xl text-muted-foreground">{text(section.props, 'body')}</p>
      ) : null}
      {text(cta, 'label') ? (
        <SafeHref
          href={text(cta, 'href', '#')}
          className="mt-5 inline-flex h-11 items-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground"
        >
          {text(cta, 'label')}
        </SafeHref>
      ) : null}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

function RichText({ resolved }: SectionProps) {
  const { section } = resolved;
  const body = text(section.props, 'body');
  if (!body) {
    return null;
  }
  // Plain paragraphs only. Rich text needs a sanitiser, and the plan defers custom CSS to Phase 9
  // behind one (D15) — shipping raw HTML now would be shipping the XSS that deferral avoids.
  const paragraphs = body.split(/\n{2,}/).filter(Boolean);
  return (
    <Section resolved={resolved}>
      {text(section.props, 'heading') ? (
        <h2 className="mb-3 text-2xl font-bold">{text(section.props, 'heading')}</h2>
      ) : null}
      <div className="space-y-3 text-sm leading-relaxed text-muted-foreground">
        {paragraphs.map((p, i) => (
          <p key={i}>{p}</p>
        ))}
      </div>
    </Section>
  );
}

function ImageWithText({ resolved }: SectionProps) {
  const { section, shop } = resolved;
  const image = sectionImageUrl(resolved);
  const right = text(section.props, 'imagePosition', 'left') === 'right';

  const copy = (
    <div>
      <h2 className="text-3xl font-semibold tracking-tight">{text(section.props, 'heading')}</h2>
      <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
        {text(section.props, 'body')}
      </p>
    </div>
  );

  /*
    Same rule as the hero: without an image this is a text block, not a caption under a grey
    rectangle that says "Add an image" to a customer.
  */
  if (!image) {
    return (
      <Section resolved={resolved}>
        <div className="max-w-2xl">{copy}</div>
      </Section>
    );
  }

  const picture = (
    <SafeImage
      src={image}
      alt=""
      loading="lazy"
      className="aspect-[4/3] w-full rounded-[var(--radius)] object-cover"
      fallbackClassName="flex aspect-[4/3] w-full items-center justify-center rounded-[var(--radius)] border border-border/70 bg-muted text-6xl font-medium uppercase tracking-[0.24em] text-primary/60"
      fallbackLabel={shop.name.slice(0, 2).toUpperCase()}
    />
  );

  return (
    <Section resolved={resolved}>
      <div className="grid items-center gap-8 md:grid-cols-2">
        {right ? (
          <>
            {copy}
            {picture}
          </>
        ) : (
          <>
            {picture}
            {copy}
          </>
        )}
      </div>
    </Section>
  );
}

function Faq({ resolved }: SectionProps) {
  const { section } = resolved;
  const items = Array.isArray(section.props?.items) ? section.props.items : [];
  if (items.length === 0) {
    return null;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <dl className="space-y-3">
        {items.map((item: Record<string, unknown>, i: number) => (
          <div key={i} className="rounded-lg border bg-card p-4">
            <dt className="font-medium">{text(item, 'q')}</dt>
            <dd className="mt-1 text-sm text-muted-foreground">{text(item, 'a')}</dd>
          </div>
        ))}
      </dl>
    </Section>
  );
}

function FeatureList({ resolved }: SectionProps) {
  const { section } = resolved;
  const items = Array.isArray(section.props?.items) ? (section.props.items as string[]) : [];
  if (items.length === 0) {
    return null;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <ul className="grid gap-3 sm:grid-cols-3">
        {items.map((item, i) => (
          <li key={i} className="rounded-lg border bg-card p-4 text-sm font-medium">
            {item}
          </li>
        ))}
      </ul>
    </Section>
  );
}

function TrustBadges({ resolved }: SectionProps) {
  const { section } = resolved;
  const items = Array.isArray(section.props?.items) ? (section.props.items as string[]) : [];
  if (items.length === 0) {
    return null;
  }

  return (
    <div className="flex flex-wrap items-center justify-center gap-x-6 gap-y-2 border-y py-4 text-xs text-muted-foreground">
      {items.map((item, i) => (
        <span key={i} className="flex items-center gap-1.5">
          <span aria-hidden className="text-primary">
            ✓
          </span>
          {item}
        </span>
      ))}
    </div>
  );
}

function Gallery({ resolved }: SectionProps) {
  const { section } = resolved;
  const mediaIds = Array.isArray(section.props?.mediaIds) ? (section.props.mediaIds as string[]) : [];
  const columns = Math.min(Math.max(num(section.props, 'columns', 3), 2), 6);

  if (mediaIds.length === 0) {
    return <EmptySection label="No images yet" />;
  }

  return (
    <Section resolved={resolved}>
      <div
        className="grid gap-3"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {mediaIds.map((id, i) => (
          <SafeImage
            key={id ?? i}
            src={id}
            alt=""
            loading="lazy"
            className="aspect-square w-full rounded-2xl object-cover"
            fallbackClassName="flex aspect-square w-full items-center justify-center rounded-2xl bg-muted text-xs text-muted-foreground"
            fallbackLabel="Image unavailable"
          />
        ))}
      </div>
    </Section>
  );
}

function Video({ resolved }: SectionProps) {
  const { section } = resolved;
  const url = text(section.props, 'url');
  if (!url) {
    return <EmptySection label="No video set" />;
  }

  // Only same-site paths and youtube/n Vimeo embeds. An arbitrary URL in a <video> or <iframe> is
  // a tracking vector on every customer page view.
  const isEmbed = /^https:\/\/(www\.)?(youtube\.com|youtu\.be|vimeo\.com)\//.test(url);
  if (!isEmbed && !url.startsWith('/')) {
    return <EmptySection label="Video URL is not an accepted source" />;
  }

  return (
    <Section resolved={resolved}>
      <div className="aspect-video w-full overflow-hidden rounded-lg bg-black">
        {isEmbed ? (
          <iframe
            src={url}
            title={text(section.props, 'title', 'Video')}
            allowFullScreen
            className="h-full w-full"
            loading="lazy"
          />
        ) : (
          // eslint-disable-next-line jsx-a11y/media-has-caption
          <video
            src={url}
            poster={text(section.props, 'posterUrl') || undefined}
            controls
            className="h-full w-full"
          />
        )}
      </div>
    </Section>
  );
}

function Map({ resolved }: SectionProps) {
  const { section, shop } = resolved;
  const query = text(section.props, 'query', shop.name);
  if (!query) {
    return null;
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading', 'Find us')} />
      {/* A link, not an embedded iframe. A third-party map iframe on every storefront page is a
          cookie banner and a tracking script the shop never asked for; a link opens it only when
          the customer wants directions. */}
      <SafeHref
        href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`}
        className="inline-flex h-10 items-center rounded-md border bg-card px-4 text-sm font-medium"
      >
        Open in Maps
      </SafeHref>
    </Section>
  );
}

function Newsletter({ resolved }: SectionProps) {
  const { section, shop } = resolved;

  return (
    <section className="rounded-lg border bg-card p-6">
      <h2 className="text-xl font-semibold">{text(section.props, 'heading', 'Stay in touch')}</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {text(section.props, 'subheading', `Updates from ${shop.name}.`)}
      </p>
      {/* Not wired in Phase 3: there is no subscriber table yet, and a form that silently discards
          an address would be worse than none. The section renders so the layout is complete. */}
      <p className="mt-4 text-xs text-muted-foreground">
        Newsletter signup arrives with notifications in Phase 4.
      </p>
    </section>
  );
}

function Countdown({ resolved }: SectionProps) {
  const { section } = resolved;
  const target = text(section.props, 'targetIso');

  if (!target) {
    return null;
  }

  const remaining = new Date(target).getTime() - Date.now();
  const past = Number.isNaN(remaining) || remaining <= 0;

  return (
    <section className="rounded-lg bg-primary px-6 py-8 text-center text-primary-foreground">
      <h2 className="text-xl font-semibold">{text(section.props, 'heading', 'Ends in')}</h2>
      <p className="mt-2 text-sm opacity-90">
        {past ? 'This offer has ended.' : 'Counting down to the end of the offer.'}
      </p>
    </section>
  );
}

function Testimonials({ resolved }: SectionProps) {
  const { section } = resolved;
  const items = Array.isArray(section.props?.items) ? section.props.items : [];

  if (items.length === 0) {
    // Reviews arrive in Phase 5. Saying so is better than an empty carousel, because an empty
    // block reads as "this shop has no customers".
    return (
      <Section resolved={resolved}>
        <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading', 'What customers say')} />
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          Customer reviews arrive in Phase 5.
        </p>
      </Section>
    );
  }

  return (
    <Section resolved={resolved}>
      <SectionHeading display={resolved.layout.display} text={text(section.props, 'heading', 'What customers say')} />
      <div className="grid gap-4 sm:grid-cols-3">
        {items.map((item: Record<string, unknown>, i: number) => (
          <figure key={i} className="rounded-lg border bg-card p-5">
            <blockquote className="text-sm leading-relaxed">{text(item, 'quote')}</blockquote>
            <figcaption className="mt-3 text-xs text-muted-foreground">{text(item, 'author')}</figcaption>
          </figure>
        ))}
      </div>
    </Section>
  );
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

function Divider({ resolved }: SectionProps) {
  const spacing = text(resolved.section.props, 'spacing', 'md');
  const pad = spacing === 'lg' ? 'my-12' : spacing === 'sm' ? 'my-4' : 'my-8';
  return <hr className={`${pad} border-t`} />;
}

function Spacer({ resolved }: SectionProps) {
  return <div style={{ height: num(resolved.section.props, 'height', 48) }} aria-hidden />;
}

/**
 * A social feed placeholder.
 *
 * <p>Phase 3's registry marks this `implemented: false`, because an Instagram feed means a
 * third-party script on every customer page view. It exists in the type union so a theme written
 * for a build that has it still renders a sensible block here rather than "unknown section".
 */
function SocialFeed() {
  return <EmptySection label="Social feed is not available yet" />;
}

function HtmlSection({ resolved }: SectionProps) {
  // Deliberately renders nothing but a notice. A `html` section with raw markup needs the
  // sanitiser the plan defers to Phase 9 (D15); rendering it now would be shipping the XSS that
  // deferral exists to prevent, and the schema accepting the prop is enough for a saved theme not
  // to lose its data in the meantime.
  const html = text(resolved.section.props, 'html');
  if (!html) {
    return null;
  }
  return <EmptySection label="Custom HTML is saved but not rendered until the sanitiser ships" />;
}

function SiteHeader({ resolved }: SectionProps) {
  const { section, shop, layout } = resolved;
  const props = section.props ?? {};
  const links = Array.isArray(props.links) ? props.links : [];
  // A header with no links still shows the shop name and a menu of the shop's own categories, so an
  // owner who adds the section and does nothing else still gets a usable storefront.
  const fallbackLinks = resolved.categories.slice(0, 5).map((c) => ({
    label: c.label,
    href: `/s/${shop.slug}/c/${c.code.toLowerCase()}`,
  }));
  const menu = links.length > 0 ? links : fallbackLinks;
  const sticky = props.sticky !== false;

  const logo =
    props.showLogo !== false && shop.logoUrl ? (
      <span className="size-9 shrink-0 overflow-hidden rounded-full bg-secondary">
        <SafeImage
          src={shop.logoUrl}
          alt=""
          loading="eager"
          className="size-full object-cover"
          fallbackClassName="grid size-full place-items-center text-[11px] font-semibold text-muted-foreground"
          fallbackLabel={shop.name.slice(0, 2).toUpperCase()}
        />
      </span>
    ) : null;

  const brand = (nameClass: string) => (
    <a href={`/s/${shop.slug}`} className="flex min-w-0 items-center gap-2.5">
      {logo}
      {props.showShopName !== false ? (
        <span className={`truncate ${nameClass}`}>{shop.name}</span>
      ) : null}
    </a>
  );

  /**
   * The menu, in three typographic registers.
   *
   * <p>The pill header styles each link as its own hoverable lozenge; the bar and masthead treat
   * the menu as a single line of text, which is what makes a commerce bar read as chrome rather
   * than as another card.
   */
  const nav = (navClass: string, itemClass: string) => (
    <nav aria-label="Shop menu" className={navClass}>
      <ul className="flex flex-wrap items-center gap-x-5 gap-y-1">
        {menu.map((item: Record<string, unknown>, i: number) => (
          <li key={`${text(item, 'label', 'link')}-${i}`}>
            <SafeHref href={text(item, 'href', '#')} className={itemClass}>
              {text(item, 'label', 'Link')}
            </SafeHref>
          </li>
        ))}
      </ul>
    </nav>
  );

  const search =
    props.showSearch === false ? null : (
      /*
        A link, not a label. This was a `<span>` containing a magnifier and the word "Search" —
        it looked like a control, could not be clicked, and had no destination. The platform's
        `/search` route did not exist either; it does now, and this points at it.
      */
      <a
        href="/search"
        className="inline-flex shrink-0 items-center gap-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden>
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" strokeLinecap="round" />
        </svg>
        <span className="hidden sm:inline">Search</span>
        <span className="sr-only sm:hidden">Search</span>
      </a>
    );

  /*
    Four header shapes, because a header is the first thing that says what kind of shop this is.
    A floating pill suits a small boutique; a bordered bar suits a dense catalogue; a centred
    wordmark suits fashion and jewellery; a borderless masthead suits a café or a homeware label.
    The bar is offset from the platform bar above it — two elements pinned to `top: 0` do not
    stack, and the shop header would slide underneath.
  */
  if (layout.header === 'bar') {
    return (
      <header
        className={`${
          sticky ? 'sticky top-[4.5rem] z-30' : ''
        } -mx-4 border-b border-border bg-background/95 px-4 backdrop-blur-xl sm:-mx-6 sm:px-6`}
        data-section="site-header"
      >
        <div className="flex h-16 items-center gap-6">
          {brand('font-semibold')}
          {nav(
            'hidden flex-1 md:block',
            'text-sm text-muted-foreground transition-colors hover:text-foreground',
          )}
          <div className="ml-auto">{search}</div>
        </div>
      </header>
    );
  }

  if (layout.header === 'centered') {
    return (
      <header
        className={`${
          sticky ? 'sticky top-[4.5rem] z-30' : ''
        } -mx-4 border-b border-border bg-background/95 px-4 backdrop-blur-xl sm:-mx-6 sm:px-6`}
        data-section="site-header"
      >
        <div className="flex flex-col items-center gap-3 py-5">
          <a
            href={`/s/${shop.slug}`}
            className="flex items-center gap-2 text-xl font-semibold tracking-[0.02em]"
          >
            {logo}
            {shop.name}
          </a>
          <div className="flex flex-wrap items-center justify-center gap-x-6 gap-y-2">
            {nav(
              '',
              'text-xs uppercase tracking-[0.14em] text-muted-foreground transition-colors hover:text-foreground',
            )}
            {search}
          </div>
        </div>
      </header>
    );
  }

  if (layout.header === 'minimal') {
    return (
      <header
        className={`${
          sticky ? 'sticky top-[4.5rem] z-30' : ''
        } -mx-4 bg-background/90 px-4 backdrop-blur-xl sm:-mx-6 sm:px-6`}
        data-section="site-header"
      >
        <div className="flex items-center justify-between gap-6 border-b border-border/60 py-5">
          <a
            href={`/s/${shop.slug}`}
            className="flex min-w-0 items-center gap-2.5 text-base font-medium uppercase tracking-[0.18em]"
          >
            {logo}
            <span className="truncate">{shop.name}</span>
          </a>
          {nav(
            'hidden md:block',
            'text-sm text-muted-foreground transition-colors hover:text-foreground',
          )}
          {search}
        </div>
      </header>
    );
  }

  return (
    <header
      className={`${sticky ? 'sticky top-[4.5rem] z-30' : ''} -mx-4 px-4 pb-3 pt-4 sm:-mx-6 sm:px-6`}
      data-section="site-header"
    >
      <div className="flex flex-wrap items-center gap-2 rounded-full border border-border/80 bg-card/90 px-2 py-2 shadow-sm backdrop-blur-xl sm:flex-nowrap">
        <a
          href={`/s/${shop.slug}`}
          className="flex min-w-0 items-center gap-2.5 rounded-full py-0.5 pl-0.5 pr-2"
        >
          {logo}
          {props.showShopName !== false ? (
            <span className="truncate font-semibold">{shop.name}</span>
          ) : null}
        </a>

        <nav aria-label="Shop menu" className="order-3 w-full sm:order-none sm:w-auto sm:flex-1">
          <ul className="flex flex-wrap items-center gap-0.5 text-sm">
            {menu.map((item: Record<string, unknown>, i: number) => (
              <li key={`${text(item, 'label', 'link')}-${i}`}>
                <SafeHref
                  href={text(item, 'href', '#')}
                  className="inline-flex rounded-full px-3 py-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
                >
                  {text(item, 'label', 'Link')}
                </SafeHref>
              </li>
            ))}
          </ul>
        </nav>

        {props.showSearch !== false ? (
          <a
            href="/search"
            className="ml-auto inline-flex shrink-0 items-center gap-2 rounded-full border border-border bg-background px-3.5 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground sm:ml-0"
          >
            <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden>
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" strokeLinecap="round" />
            </svg>
            <span className="hidden sm:inline">Search</span>
            <span className="sr-only sm:hidden">Search</span>
          </a>
        ) : null}
      </div>
    </header>
  );
}

function SiteFooter({ resolved }: SectionProps) {
  const { section, shop } = resolved;
  const props = section.props ?? {};
  const columns = Array.isArray(props.columns) ? props.columns : [];
  const year = new Date().getFullYear();

  return (
    <footer
      className="mt-14 rounded-[1.75rem] border border-border bg-card px-6 py-10 sm:px-10"
      data-section="site-footer"
    >
      <div className="flex flex-wrap gap-10">
        <div className="min-w-[220px] flex-1">
          <div className="flex items-center gap-2.5">
            <span className="grid size-8 place-items-center rounded-full bg-primary text-[11px] font-semibold text-primary-foreground">
              {shop.name.slice(0, 2).toUpperCase()}
            </span>
            <p className="font-semibold">{shop.name}</p>
          </div>
          {text(props, 'about') || shop.description ? (
            <p className="mt-4 max-w-sm text-sm leading-relaxed text-muted-foreground">
              {text(props, 'about') || shop.description}
            </p>
          ) : null}
        </div>

        {columns.map((col: Record<string, unknown>, i: number) => {
          const items = Array.isArray(col.links) ? col.links : [];
          return (
            <div key={`${text(col, 'title', 'col')}-${i}`}>
              <p className="text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">
                {text(col, 'title', 'Links')}
              </p>
              <ul className="mt-4 space-y-2.5 text-sm">
                {items.map((item: Record<string, unknown>, j: number) => (
                  <li key={`${text(item, 'label', 'l')}-${j}`}>
                    <SafeHref
                      href={text(item, 'href', '#')}
                      className="text-muted-foreground transition-colors hover:text-primary"
                    >
                      {text(item, 'label', 'Link')}
                    </SafeHref>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </div>

      <div className="mt-9 flex flex-wrap items-center justify-between gap-3 border-t border-border/70 pt-6 text-xs text-muted-foreground">
        <p>{text(props, 'copyright') || `© ${year} ${shop.name}`}</p>
        {props.showPaymentBadges !== false ? (
          <p className="inline-flex items-center gap-2">
            <span className="rounded-full border border-border px-2.5 py-1">eSewa</span>
            <span className="rounded-full border border-border px-2.5 py-1">Cash on delivery</span>
            <span className="rounded-full border border-border px-2.5 py-1">Bank transfer</span>
          </p>
        ) : null}
      </div>
    </footer>
  );
}

/**
 * Shown when a theme references a section this build does not have.
 *
 * <p>The important property is that it renders *something visible* rather than throwing. A theme
 * written by a newer build must degrade to one obvious gap on a shop's page, not a 500.
 */
export function UnknownSection({ resolved }: SectionProps) {
  return (
    <div className="rounded-lg border border-dashed p-6 text-center">
      <p className="text-sm font-medium">
        This page uses a section this platform does not support yet.
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        Section type: {resolved.section.type}. Everything else on the page still works.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Layout helpers
// ---------------------------------------------------------------------------

function Section({ resolved, children }: { resolved?: ResolvedSection; children: ReactNode }) {
  /*
    A theme that turns bands on gets a tinted stripe for every section whose props say
    `tone: "muted"`. The negative margin cancels the renderer's gutter and the padding restores
    it, so the stripe reaches the edge of the page column without any section knowing how wide
    the column is. Alternating stripes are what give a dense catalogue a rhythm; without them a
    long page of product grids reads as one undifferentiated scroll.
  */
  const banded =
    resolved?.layout.bands === true &&
    text(resolved.section.props, 'tone', 'default') === 'muted';
  /*
    The wrapper carries the section id. Every template's hero CTA is `href="#products"` and its
    menu uses `#categories`, and browsers scroll to an element with that id — without one here,
    those links were no-ops that looked like navigation. The only section that set an id of its
    own was the FAQ, which would have been the one working anchor in the catalogue.
  */
  return (
    <section
      id={resolved?.section.id}
      className={banded ? '-mx-4 bg-muted px-4 py-12 sm:-mx-6 sm:px-6' : 'py-8'}
    >
      {children}
    </section>
  );
}

/**
 * A section heading in the theme's display register.
 *
 * <p>Three scales, not one enlarged one: `editorial` is a light, large, tightly tracked headline
 * for fashion and jewellery; `compact` is small caps for a dense catalogue where headings are
 * signposts, not statements; `default` stays in the middle.
 */
function SectionHeading({
  text: heading,
  sub,
  display = 'default',
}: {
  text: string;
  sub?: string;
  display?: 'default' | 'editorial' | 'compact';
}) {
  if (!heading && !sub) {
    return null;
  }
  const headingClass =
    display === 'editorial'
      ? 'text-3xl font-medium tracking-[-0.02em] sm:text-4xl'
      : display === 'compact'
        ? 'text-lg font-semibold uppercase tracking-[0.14em]'
        : 'text-2xl font-bold tracking-tight';
  return (
    <div className="mb-6">
      {heading ? <h2 className={headingClass}>{heading}</h2> : null}
      {sub ? <p className="mt-2 text-sm text-muted-foreground">{sub}</p> : null}
    </div>
  );
}

function EmptySection({ label }: { label: string }) {
  return (
    <div className="my-4 rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      {label}
    </div>
  );
}

// ---------------------------------------------------------------------------
// The closed registry
// ---------------------------------------------------------------------------

/**
 * Every section type maps to exactly one component (D8).
 *
 * A `Record` rather than a lookup with a fallback, so adding a type to `SECTION_TYPES` without
 * writing its component is a **compile error** rather than a blank space discovered in
 * production. `unknown-section` is the one deliberate hole, and it is in the map.
 */
export const SECTION_COMPONENTS: Record<SectionType, (props: SectionProps) => ReactNode> = {
  'announcement-bar': AnnouncementBar,
  'site-header': SiteHeader,
  hero: Hero,
  'category-grid': CategoryGrid,
  'product-carousel': ProductCarousel,
  'product-grid': ProductGrid,
  'collection-list': CollectionList,
  'promo-banner': PromoBanner,
  'image-with-text': ImageWithText,
  'rich-text': RichText,
  video: Video,
  gallery: Gallery,
  testimonials: Testimonials,
  faq: Faq,
  newsletter: Newsletter,
  countdown: Countdown,
  'trust-badges': TrustBadges,
  'feature-list': FeatureList,
  'cta-banner': CtaBanner,
  map: Map,
  'social-feed': SocialFeed,
  divider: Divider,
  spacer: Spacer,
  html: HtmlSection,
  'site-footer': SiteFooter,
  'unknown-section': UnknownSection,
};

/** Whether a type has a real component or is the graceful-degradation placeholder. */
export function hasComponent(type: string): boolean {
  return type !== 'unknown-section';
}
