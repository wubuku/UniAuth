# Legacy SQL Assets

> Status: Historical
> Archived: 2026-08-07

These files are retained for provenance only. They are not an executable migration chain.
Keep the archived SQL text unchanged. Put safety warnings and current guidance in this index
instead of editing the archived source files.

## Why They Were Archived

- The sequence contains V1-V4 and V6-V8, with no V5.
- V3 rebuilds `users` with a SQLite integer primary key and is incompatible with the
  deployed PostgreSQL UUID-string model.
- Other files mix database-specific types, stale columns, and schema assumptions.
- The former runtime schema files were incomplete or diverged from the deployed auth schema.
- Flyway now scans only `classpath:db/migration/postgresql`.

## Current Source Of Truth

- Runtime baseline: [`V1__baseline_uniauth_auth_schema.sql`](../../../../src/main/resources/db/migration/postgresql/V1__baseline_uniauth_auth_schema.sql)
- Current hardening migration: [`V2__harden_login_method_invariants.sql`](../../../../src/main/resources/db/migration/postgresql/V2__harden_login_method_invariants.sql)
- Live database guidance: [configuration baseline](../../../CONFIGURATION.md)
- Migration and verification plan: [hardening implementation plan](../../../drafts/HARDENING_IMPLEMENTATION_PLAN.md)

The files under `migrations/` and `runtime/` must not be executed against current databases.
