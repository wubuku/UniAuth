const DEFAULT_RETURN_PATH = '/';

export function resolveLoginReturnPath(
  search: string,
  currentOrigin: string,
): string {
  const returnTo = new URLSearchParams(search).get('returnTo');
  if (!returnTo || !returnTo.startsWith('/') || returnTo.startsWith('//')) {
    return DEFAULT_RETURN_PATH;
  }

  try {
    const target = new URL(returnTo, currentOrigin);
    if (target.origin !== currentOrigin) {
      return DEFAULT_RETURN_PATH;
    }
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return DEFAULT_RETURN_PATH;
  }
}
