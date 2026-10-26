import { create } from 'zustand';

import type { ThemeConfig, ThemeSection } from '@store-app/theme-engine';

/**
 * Theme editor draft state.
 *
 * Edits happen here and are published explicitly. A live storefront must never be half-edited:
 * the shop owner drags a section around, and a customer mid-scroll would otherwise see the
 * layout jump. So the draft stays local until `markSaved()`.
 *
 * Undo/redo holds whole config snapshots. Themes are a few KB of JSON, so copying is cheaper
 * and far less bug-prone than inverse patches — and it makes an undo always correct rather than
 * approximately correct.
 */

const HISTORY_LIMIT = 50;

interface ThemeEditorState {
  draft: ThemeConfig | null;
  saved: ThemeConfig | null;
  past: ThemeConfig[];
  future: ThemeConfig[];
  selectedSectionId: string | null;
  dirty: boolean;
  saving: boolean;

  load(config: ThemeConfig | null): void;
  updateTokens(tokens: Partial<ThemeConfig['tokens']>): void;
  addSection(section: ThemeSection, atIndex?: number): void;
  updateSection(id: string, patch: Partial<ThemeSection>): void;
  removeSection(id: string): void;
  moveSection(id: string, toIndex: number): void;
  select(id: string | null): void;
  undo(): void;
  redo(): void;
  setSaving(saving: boolean): void;
  /** Call after a successful save; clears the dirty flag and the history. */
  markSaved(): void;
  reset(): void;
}

/**
 * Whether two theme documents are the same.
 *
 * <p>`dirty` used to be set to `true` by undo as well as by every edit, so undoing your way back
 * to the published document still claimed there were unpublished changes and left Publish
 * enabled — the owner then republished an identical theme just to clear the badge. Comparing the
 * draft against `saved` is the only definition of "changed" that survives an undo.
 */
function sameConfig(a: ThemeConfig | null, b: ThemeConfig | null): boolean {
  if (!a || !b) return a === b;
  return JSON.stringify(a) === JSON.stringify(b);
}

function clone(config: ThemeConfig | null): ThemeConfig | null {
  return config ? (structuredClone(config) as ThemeConfig) : null;
}

export const useThemeEditor = create<ThemeEditorState>()((set, get) => ({
  draft: null,
  saved: null,
  past: [],
  future: [],
  selectedSectionId: null,
  dirty: false,
  saving: false,

  load(config) {
    set({
      draft: clone(config),
      saved: clone(config),
      past: [],
      future: [],
      selectedSectionId: null,
      dirty: false,
    });
  },

  updateTokens(tokens) {
    const { draft } = get();
    if (!draft) return;
    set((state) => ({
      past: state.draft ? [...state.past, clone(state.draft)!].slice(-HISTORY_LIMIT) : state.past,
      future: [],
      draft: { ...draft, tokens: { ...draft.tokens, ...tokens } },
      dirty: true,
    }));
  },

  addSection(section, atIndex) {
    const { draft } = get();
    if (!draft) return;
    set((state) => ({
      past: state.draft ? [...state.past, clone(state.draft)!].slice(-HISTORY_LIMIT) : state.past,
      future: [],
      draft: {
        ...draft,
        sections: (() => {
          const sections = [...draft.sections];
          sections.splice(atIndex ?? sections.length, 0, section);
          return sections;
        })(),
      },
      selectedSectionId: section.id,
      dirty: true,
    }));
  },

  updateSection(id, patch) {
    const { draft } = get();
    if (!draft) return;
    set((state) => ({
      past: state.draft ? [...state.past, clone(state.draft)!].slice(-HISTORY_LIMIT) : state.past,
      future: [],
      draft: {
        ...draft,
        sections: draft.sections.map((s) => (s.id === id ? { ...s, ...patch } : s)),
      },
      dirty: true,
    }));
  },

  removeSection(id) {
    const { draft } = get();
    if (!draft) return;
    set((state) => ({
      past: state.draft ? [...state.past, clone(state.draft)!].slice(-HISTORY_LIMIT) : state.past,
      future: [],
      draft: { ...draft, sections: draft.sections.filter((s) => s.id !== id) },
      selectedSectionId: state.selectedSectionId === id ? null : state.selectedSectionId,
      dirty: true,
    }));
  },

  moveSection(id, toIndex) {
    const { draft } = get();
    if (!draft) return;
    set((state) => {
      const from = draft.sections.findIndex((s) => s.id === id);
      if (from === -1) return state;
      const sections = [...draft.sections];
      const moved = sections.splice(from, 1)[0];
      if (!moved) return state;
      sections.splice(Math.max(0, Math.min(toIndex, sections.length)), 0, moved);
      return {
        past: state.draft ? [...state.past, clone(state.draft)!].slice(-HISTORY_LIMIT) : state.past,
        future: [],
        draft: { ...draft, sections },
        dirty: true,
      };
    });
  },

  select(id) {
    set({ selectedSectionId: id });
  },

  undo() {
    const { past, draft } = get();
    const previous = past[past.length - 1];
    if (!previous) return;
    set((state) => ({
      past: state.past.slice(0, -1),
      future: draft ? [...state.future, draft] : state.future,
      draft: previous,
      dirty: !sameConfig(previous, state.saved),
    }));
  },

  redo() {
    const { future, draft } = get();
    const next = future[future.length - 1];
    if (!next) return;
    set((state) => ({
      past: draft ? [...state.past, draft] : state.past,
      future: state.future.slice(0, -1),
      draft: next,
      dirty: !sameConfig(next, state.saved),
    }));
  },

  setSaving(saving) {
    set({ saving });
  },

  markSaved() {
    set({ saved: clone(get().draft), dirty: false, past: [], future: [] });
  },

  reset() {
    set({
      draft: clone(get().saved),
      past: [],
      future: [],
      dirty: false,
      selectedSectionId: null,
    });
  },
}));
