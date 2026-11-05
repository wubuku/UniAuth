import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Component-level unit tests run in jsdom and share the React plugin with the
// production Vite build. The Playwright suites keep their own configs so the
// browser gate is unaffected.
export default defineConfig({
  plugins: [react()],
  define: {
    'import.meta.env.VITE_AUTH_DIAGNOSTICS': JSON.stringify('true'),
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(''),
  },
  test: {
    environment: 'jsdom',
    include: ['tests/unit/**/*.test.{ts,tsx}'],
    setupFiles: ['tests/unit/setup.ts'],
    clearMocks: true,
    restoreMocks: true,
    // The JSON report is the only evidence `scripts/health-snapshot.py` trusts
    // for frontend test totals. It records what actually ran (parameterized
    // cases expand at runtime), unlike `vitest list`, and lives outside
    // Playwright's `test-results` directory so Playwright cleanup cannot delete
    // it between stages.
    reporters: ['default', 'json'],
    outputFile: { json: 'reports/vitest-results.json' },
    // Coverage is opt-in via `npm run test:unit:coverage` and is report-only:
    // there are no thresholds, so a low number never fails the unit suite.
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'text', 'json-summary', 'html'],
      reportsDirectory: 'coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.d.ts', 'src/vite-env.d.ts'],
    },
  },
})
