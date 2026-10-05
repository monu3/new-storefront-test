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

function ProductCard({ product }: { product: ProductSummary }) {
  const discounted = product.hasDiscount || (product.compareAt ?? 0) > product.price;

  return (
    <div className="group flex flex-col overflow-hidden rounded-lg border bg-card text-card-foreground">
      <div className="aspect-square w-full overflow-hidden bg-muted">
        {product.imageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={product.imageUrl}
            alt={product.name}
            loading="lazy"
            className="h-full w-full object-cover transition-transform group-hover:scale-105"
          />
        ) : (
          <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
            No image
          </div>
        )}
      </div>
      <div className="flex flex-1 flex-col gap-1 p-3">
        {product.categoryLabel ? (
          <span className="text-xs text-muted-foreground">{product.categoryLabel}</span>
        ) : null}
        <h3 className="line-clamp-2 text-sm font-medium leading-snug">{product.name}</h3>
        <div className="mt-auto flex items-baseline gap-2 pt-2">
          <span className="text-sm font-semibold">{formatPrice(product.price)}</span>
          {discounted && product.compareAt ? (
            <span className="text-xs text-muted-foreground line-through">
              {formatPrice(product.compareAt)}
            </span>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function ProductGrid({ resolved }: SectionProps) {
  const { section, products } = resolved;
  const columns = Math.min(Math.max(num(section.props, 'columns', 4), 1), 6);

  if (products.length === 0) {
    return <EmptySection label="No products to show yet" />;
  }

  return (
    <Section>
      <SectionHeading text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <div
        className="grid gap-4"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {products.map((p) => (
          <ProductCard key={p.id ?? p.name} product={p} />
        ))}
      </div>
    </Section>
  );
}

function ProductCarousel({ resolved }: SectionProps) {
  const { section, products } = resolved;

  if (products.length === 0) {
    return <EmptySection label="No products to show yet" />;
  }

  return (
    <Section>
      <SectionHeading text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      {/* A scroll-snap row rather than a carousel with JS. The content is in the HTML either
          way, and a customer's swipe is a scroll the browser already handles. */}
      <div className="flex snap-x snap-mandatory gap-4 overflow-x-auto pb-2">
        {products.map((p) => (
          <div key={p.id ?? p.name} className="w-56 shrink-0 snap-start">
            <ProductCard product={p} />
          </div>
        ))}
      </div>
    </Section>
  );
}

function CategoryGrid({ resolved }: SectionProps) {
  const { section, categories, shop } = resolved;
  const columns = Math.min(Math.max(num(section.props, 'columns', 4), 2), 6);

  if (categories.length === 0) {
    return <EmptySection label="No categories yet" />;
  }

  return (
    <Section>
      <SectionHeading text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <div
        className="grid gap-3"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {categories.map((c) => (
          <SafeHref
            key={c.code}
            href={`/s/${shop.slug}/c/${c.code.toLowerCase()}`}
            className="flex flex-col items-center justify-center gap-1 rounded-lg border bg-card p-6 text-center transition-colors hover:bg-muted"
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
    <Section>
      <SectionHeading text={text(section.props, 'heading')} />
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
  const { section, shop } = resolved;
  const image = sectionImageUrl(resolved);
  const cta = (section.props?.cta ?? {}) as Record<string, unknown>;
  const centred = section.variant === 'centered';

  const copy = (
    <div className={centred ? 'mx-auto max-w-2xl text-center' : ''}>
      <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">
        {text(section.props, 'heading', shop.name)}
      </h1>
      {text(section.props, 'subheading') ? (
        <p className="mt-3 text-base text-muted-foreground">
          {text(section.props, 'subheading')}
        </p>
      ) : null}
      {text(cta, 'label') ? (
        <SafeHref
          href={text(cta, 'href', '#products')}
          className="mt-6 inline-flex h-11 items-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground"
        >
          {text(cta, 'label')}
        </SafeHref>
      ) : null}
    </div>
  );

  const picture = image ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={image}
      alt=""
      className="aspect-[4/3] w-full rounded-lg object-cover"
      loading="eager"
    />
  ) : (
    // A placeholder rather than a broken image: a shop that has not uploaded a hero photograph
    // still gets a homepage that looks deliberate.
    <div className="flex aspect-[4/3] w-full items-center justify-center rounded-lg bg-muted text-sm text-muted-foreground">
      Add a hero image
    </div>
  );

  return (
    <section className="pt-6" id={text(section.props, 'anchor', 'hero')}>
      {centred ? (
        <div className="flex flex-col gap-6">
          {copy}
          {picture}
        </div>
      ) : (
        <div className="grid items-center gap-8 md:grid-cols-2">
          {copy}
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
    <section className="relative overflow-hidden rounded-lg">
      {image ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={image} alt="" loading="lazy" className="h-56 w-full object-cover sm:h-72" />
      ) : (
        <div className="h-56 w-full bg-muted sm:h-72" />
      )}
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
    <Section>
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
  const { section } = resolved;
  const image = sectionImageUrl(resolved);
  const right = text(section.props, 'imagePosition', 'left') === 'right';

  const picture = image ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={image} alt="" loading="lazy" className="w-full rounded-lg object-cover" />
  ) : (
    <div className="flex aspect-[4/3] w-full items-center justify-center rounded-lg bg-muted text-sm text-muted-foreground">
      Add an image
    </div>
  );

  return (
    <section className={`grid items-center gap-8 md:grid-cols-2 ${right ? '' : ''}`}>
      {right ? (
        <>
          <div>
            <h2 className="text-2xl font-bold">{text(section.props, 'heading')}</h2>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
              {text(section.props, 'body')}
            </p>
          </div>
          {picture}
        </>
      ) : (
        <>
          {picture}
          <div>
            <h2 className="text-2xl font-bold">{text(section.props, 'heading')}</h2>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
              {text(section.props, 'body')}
            </p>
          </div>
        </>
      )}
    </section>
  );
}

function Faq({ resolved }: SectionProps) {
  const { section } = resolved;
  const items = Array.isArray(section.props?.items) ? section.props.items : [];
  if (items.length === 0) {
    return null;
  }

  return (
    <Section>
      <SectionHeading text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
      <dl className="space-y-3" id={section.id}>
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
    <Section>
      <SectionHeading text={text(section.props, 'heading')} sub={text(section.props, 'subheading')} />
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
    <Section>
      <div
        className="grid gap-3"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {mediaIds.map((id, i) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={id ?? i} src={id} alt="" loading="lazy" className="aspect-square w-full rounded-lg object-cover" />
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
    <Section>
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
    <Section>
      <SectionHeading text={text(section.props, 'heading', 'Find us')} />
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
      <Section>
        <SectionHeading text={text(section.props, 'heading', 'What customers say')} />
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          Customer reviews arrive in Phase 5.
        </p>
      </Section>
    );
  }

  return (
    <Section>
      <SectionHeading text={text(section.props, 'heading', 'What customers say')} />
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
  const { section, shop } = resolved;
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

  return (
    <header
      className={`border-b bg-background ${sticky ? 'sticky top-0 z-40' : ''}`}
      data-section="site-header"
    >
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-4 py-3">
        <div className="flex min-w-0 items-center gap-2">
          {props.showLogo !== false && shop.logoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={shop.logoUrl} alt="" className="size-8 shrink-0 rounded object-cover" />
          ) : null}
          {props.showShopName !== false ? (
            <span className="truncate font-semibold">{shop.name}</span>
          ) : null}
        </div>

        <nav aria-label="Shop menu" className="order-3 w-full sm:order-2 sm:w-auto sm:flex-1">
          <ul className="flex flex-wrap items-center gap-1 text-sm">
            {menu.map((item: Record<string, unknown>, i: number) => (
              <li key={`${text(item, 'label', 'link')}-${i}`}>
                <SafeHref
                  href={text(item, 'href', '#')}
                  className="rounded-md px-2.5 py-1.5 transition-colors hover:bg-muted"
                >
                  {text(item, 'label', 'Link')}
                </SafeHref>
              </li>
            ))}
            {menu.length === 0 ? (
              <li className="px-2.5 py-1.5 text-xs text-muted-foreground">
                Add links, or publish some products so your categories appear here.
              </li>
            ) : null}
          </ul>
        </nav>

        {props.showSearch !== false ? (
          <span className="order-2 ml-auto inline-flex items-center gap-1.5 text-sm text-muted-foreground sm:order-3">
            <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden>
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" strokeLinecap="round" />
            </svg>
            Search
          </span>
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
    <footer className="mt-12 border-t bg-muted/30" data-section="site-footer">
      <div className="mx-auto max-w-6xl px-4 py-10">
        <div className="flex flex-wrap gap-8">
          <div className="min-w-[220px] flex-1">
            <p className="font-semibold">{shop.name}</p>
            {text(props, 'about') || shop.description ? (
              <p className="mt-2 text-sm text-muted-foreground">
                {text(props, 'about') || shop.description}
              </p>
            ) : null}
          </div>

          {columns.map((col: Record<string, unknown>, i: number) => {
            const items = Array.isArray(col.links) ? col.links : [];
            return (
              <div key={`${text(col, 'title', 'col')}-${i}`}>
                <p className="text-sm font-medium">{text(col, 'title', 'Links')}</p>
                <ul className="mt-2 space-y-1.5 text-sm">
                  {items.map((item: Record<string, unknown>, j: number) => (
                    <li key={`${text(item, 'label', 'l')}-${j}`}>
                      <SafeHref href={text(item, 'href', '#')} className="text-muted-foreground hover:text-foreground">
                        {text(item, 'label', 'Link')}
                      </SafeHref>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>

        <div className="mt-8 flex flex-wrap items-center justify-between gap-3 border-t pt-6 text-xs text-muted-foreground">
          <p>{text(props, 'copyright') || `© ${year} ${shop.name}`}</p>
          {props.showPaymentBadges !== false ? (
            <p className="inline-flex items-center gap-2">
              <span className="rounded border px-1.5 py-0.5">eSewa</span>
              <span className="rounded border px-1.5 py-0.5">Cash on delivery</span>
              <span className="rounded border px-1.5 py-0.5">Bank transfer</span>
            </p>
          ) : null}
        </div>
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

function Section({ children }: { children: ReactNode }) {
  return <section className="py-8">{children}</section>;
}

function SectionHeading({ text: heading, sub }: { text: string; sub?: string }) {
  if (!heading && !sub) {
    return null;
  }
  return (
    <div className="mb-5">
      {heading ? <h2 className="text-2xl font-bold tracking-tight">{heading}</h2> : null}
      {sub ? <p className="mt-1 text-sm text-muted-foreground">{sub}</p> : null}
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
