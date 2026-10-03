/**
 * The UI kit.
 *
 * Ported from the reference storefront project's `@doko/shared` package — a shadcn/ui component
 * set consumed as TypeScript source rather than from npm. **The reference is read-only**: files are
 * copied out of it and adapted here, never edited in place.
 *
 * ## What the reference actually uses
 *
 * Measured across its 198 source files, its UI is overwhelmingly plain shadcn: `button` in 80
 * files, `card` in 36, `input` in 30, `label` in 23. Its own bespoke `Card/`, `Skeleton/` and
 * `Dropdown/` components are used in two, one and one files respectively. So the shadcn primitives
 * are the design language to match, and the bespoke set is kept as `legacy-*` rather than
 * promoted.
 *
 * ## What changed in the port, and why
 *
 * The kit targets React 18, Tailwind v3 and a looser tsconfig. This project is React 19, Tailwind
 * v4 and stricter. Every adaptation is mechanical and commented at its site:
 *
 * - `verbatimModuleSyntax` — type-only imports are marked as such.
 * - `noUncheckedIndexedAccess` — an array read is `T | undefined`, so reads are narrowed or guarded
 *   instead of asserted.
 * - React 19 `useRef` requires an explicit initial value.
 * - Radix, `cmdk`, `input-otp`, `motion`, `recharts` and friends are now declared as real
 *   dependencies of this package. The reference's shared package declares none, so a component
 *   there that imports `recharts` only works because some app happens to depend on it — a missing
 *   dependency waiting to be discovered as a blank screen.
 * - `@shared/*` became relative paths: the reference resolves them through a TS alias with no build
 *   step, which does not survive being consumed as a workspace dependency.
 * - `Card/` and `Skeleton/` became `legacy-card/` and `legacy-skeleton/`. On a case-insensitive
 *   filesystem `Card/` and `card.tsx` are the same path, and TypeScript refuses to include both in
 *   one program. Renaming keeps both components instead of dropping one.
 *
 * The first pass of that measurement was wrong in an instructive way: it counted which components
 * the reference's *application* files import, and on that basis `MenuItem` and `Spinner` looked
 * unused. `Dropdown` imports `MenuItem`, so it is used. A usage count taken from the outside misses
 * the dependency graph entirely — which is why the drop list was re-checked by compiling rather
 * than by trusting the number.
 *
 * ## Two components with the same name
 *
 * `Card` and `Skeleton` each exist twice — once as shadcn, once as the reference's own variant.
 * The shadcn one is exported under the plain name, because that is what the reference's pages and
 * this project's pages both import 36+ times. The variants are `LegacyCard` and `LegacySkeleton`.
 */

export * from './lib/utils';

// --- shadcn primitives ---------------------------------------------------------------
export * from './ui/alert';
export * from './ui/alert-dialog';
export * from './ui/avatar';
export * from './ui/badge';
export * from './ui/button';
export * from './ui/card';
export * from './ui/checkbox';
export * from './ui/collapsible';
export * from './ui/command';
export * from './ui/dialog';
export * from './ui/dropdown-menu';
export * from './ui/form';
export * from './ui/input';
export * from './ui/input-otp';
export * from './ui/label';
export * from './ui/popover';
export * from './ui/progress';
export * from './ui/radio-group';
export * from './ui/resizable';
export * from './ui/scroll-area';
export * from './ui/select';
export * from './ui/separator';
export * from './ui/sheet';
export * from './ui/sidebar';
export * from './ui/skeleton';
export * from './ui/slider';
export * from './ui/switch';
export * from './ui/table';
export * from './ui/tabs';
export * from './ui/textarea';
export * from './ui/toast';
export * from './ui/toaster';
export * from './ui/tooltip';

// --- the reference's own extras ------------------------------------------------------
export * from './ui/infinite-slider';
export * from './ui/progressive-blur';
export * from './ui/logo-cloud';
export * from './ui/text-hover-effect';
export * from './ui/chart';

// --- bespoke components, renamed to avoid the casing collisions -------------------------
export { default as LegacyCard } from './ui/legacy-card';
export type { CardProps as LegacyCardProps } from './ui/legacy-card';
export { default as LegacySkeleton } from './ui/legacy-skeleton';
export type { SkeletonProps as LegacySkeletonProps } from './ui/legacy-skeleton';
export { default as Dropdown } from './ui/Dropdown';
export { default as MenuItem } from './ui/MenuItem';
export type { DropdownProps, DropdownRef } from './ui/Dropdown';
export { ConfigProvider, useConfig } from './ui/ConfigProvider';

// --- hooks ------------------------------------------------------------------------------
export * from './hooks/use-toast';
export * from './hooks/use-mobile';
export { default as useUniqueId } from './hooks/useUniqueId';
export { default as useCallbackRef } from './hooks/useCallbackRef';
export { default as useMergeRef } from './hooks/useMergeRef';
export { default as useControllableState } from './hooks/useControllableState';
export { default as useDidUpdate } from './hooks/useDidUpdate';
export { default as useTimeout } from './hooks/useTimeout';
