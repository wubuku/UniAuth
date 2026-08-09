declare module 'virtual:diagnostics-routes' {
  import type {
    ComponentType,
    LazyExoticComponent,
  } from 'react';

  export const diagnosticsEnabled: boolean;
  export const TestPage:
    LazyExoticComponent<ComponentType> | null;
  export const ResourceTestPage:
    LazyExoticComponent<ComponentType> | null;
  export const DiagnosticsHomeLinks:
    LazyExoticComponent<ComponentType> | null;
}
