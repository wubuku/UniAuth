\set ON_ERROR_STOP on

WITH selected_tables(table_name) AS (
    VALUES
        ('users'),
        ('user_login_methods'),
        ('web3_nonces'),
        ('email_verification_codes'),
        ('email_delivery_outbox'),
        ('auth_rate_limits'),
        ('security_events'),
        ('user_authorities'),
        ('token_blacklist'),
        ('spring_session'),
        ('spring_session_attributes')
),
schema_objects AS (
    SELECT
        'column' AS object_type,
        table_class.relname AS table_name,
        attribute.attnum::text || ':' || attribute.attname AS object_name,
        concat_ws(
            '|',
            format_type(attribute.atttypid, attribute.atttypmod),
            attribute.attnotnull::text,
            coalesce(pg_get_expr(default_value.adbin, default_value.adrelid), '')
        ) AS definition
    FROM pg_attribute attribute
    JOIN pg_class table_class
      ON table_class.oid = attribute.attrelid
    JOIN pg_namespace table_namespace
      ON table_namespace.oid = table_class.relnamespace
    JOIN selected_tables
      ON selected_tables.table_name = table_class.relname
    LEFT JOIN pg_attrdef default_value
      ON default_value.adrelid = attribute.attrelid
     AND default_value.adnum = attribute.attnum
    WHERE table_namespace.nspname = 'public'
      AND table_class.relkind = 'r'
      AND attribute.attnum > 0
      AND NOT attribute.attisdropped

    UNION ALL

    SELECT
        'constraint',
        table_class.relname,
        table_constraint.conname,
        replace(
            replace(
                replace(
                    pg_get_constraintdef(table_constraint.oid, true),
                    '::character varying::text',
                    ''
                ),
                '::character varying',
                ''
            ),
            '::text[]',
            ''
        )
    FROM pg_constraint table_constraint
    JOIN pg_class table_class
      ON table_class.oid = table_constraint.conrelid
    JOIN pg_namespace table_namespace
      ON table_namespace.oid = table_class.relnamespace
    JOIN selected_tables
      ON selected_tables.table_name = table_class.relname
    WHERE table_namespace.nspname = 'public'

    UNION ALL

    SELECT
        'index',
        indexes.tablename,
        indexes.indexname,
        indexes.indexdef
    FROM pg_indexes indexes
    JOIN selected_tables
      ON selected_tables.table_name = indexes.tablename
    WHERE indexes.schemaname = 'public'
)
SELECT object_type, table_name, object_name, definition
FROM schema_objects
ORDER BY object_type, table_name, object_name, definition;
