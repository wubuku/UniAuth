# Database Archive

> Status: Historical
> Archived: 2026-08-07

This directory preserves database assets that are no longer loaded by the application.
The live PostgreSQL schema is owned only by Flyway migrations under
`src/main/resources/db/migration/postgresql/`.

## Legacy SQL

[`legacy-sql/`](legacy-sql/README.md) contains:

- the former V1-V4 and V6-V8 migration sequence (there was no V5);
- the former PostgreSQL and SQLite runtime schema files;
- the former PostgreSQL and SQLite data initialization files.

Do not copy these files back to the runtime classpath or execute them against a current
database. They mix incompatible PostgreSQL and SQLite assumptions and predate the approved
Flyway baseline.
