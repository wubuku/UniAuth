/**
 * Single seam for full-page navigations.
 *
 * Components must not assign `window.location.href` directly: jsdom cannot
 * observe a real navigation, so a direct assignment is only testable through
 * Playwright. Routing every redirect through this module gives Vitest a
 * injectable `assign` target while production keeps the exact same behavior
 * (`location.assign` performs the same navigation as `location.href =`).
 */
export interface NavigationLocation {
  assign(url: string): void;
}

export function navigateTo(
  url: string,
  location: NavigationLocation = window.location
): void {
  location.assign(url);
}
