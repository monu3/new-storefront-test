/**
 * ESLint config for the Next.js customer portal.
 *
 * The important rule here is the import boundary. `packages/*` may never import from
 * `apps/*`: the packages are meant to be reusable and app-agnostic, and a reverse import
 * creates a cycle that Vite and Next resolve differently. Catching it in lint is far cheaper
 * than debugging a build that only fails in one of the two apps.
 */

const boundaryRules = {
  'no-restricted-imports': ['error', {
    patterns: [
      {
        group: ['**/apps/*'],
        message:
          'packages/* must not import from apps/*. Shared code stays app-agnostic; move the ' +
          'shared piece into a package instead.',
      },
      {
        group: ['../../../apps/*', '../../apps/*', '../../apps/**'],
        message: 'packages/* must not import from apps/*.',
      },
    ],
  }],
};

/** Files that are allowed to reach into apps/ — the two entrypoints only. */
module.exports = [
  {
    ignores: ['node_modules/**', '.next/**', '.turbo/**', 'dist/**'],
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: {
      // Rules are declared inline below; no plugin resolution needed yet.
    },
    rules: {
      'no-restricted-imports': boundaryRules['no-restricted-imports'],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      eqeqeq: ['error', 'smart'],
      'prefer-const': 'error',
      'no-var': 'error',
    },
  },
  {
    // The boundary rule must NOT apply to the apps themselves, which legitimately
    // import both packages and their own files.
    files: ['apps/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },
];
