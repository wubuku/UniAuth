WITH selected_tables(table_name) AS (
    VALUES
        ('users'),
        ('user_login_methods'),
        ('web3_nonces'),
        ('web3_challenge_counters'),
        ('email_verification_codes'),
        ('email_delivery_outbox'),
        ('auth_rate_limits'),
        ('security_events'),
        ('token_families'),
        ('oauth2_binding_intents'),
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

    UNION ALL

    SELECT
        'trigger',
        table_class.relname,
        trigger_row.tgname,
        pg_get_triggerdef(trigger_row.oid, true)
    FROM pg_trigger trigger_row
    JOIN pg_class table_class
      ON table_class.oid = trigger_row.tgrelid
    JOIN pg_namespace table_namespace
      ON table_namespace.oid = table_class.relnamespace
    JOIN selected_tables
      ON selected_tables.table_name = table_class.relname
    WHERE table_namespace.nspname = 'public'
      AND NOT trigger_row.tgisinternal

    UNION ALL

    SELECT
        'function',
        '',
        procedure_row.proname,
        pg_get_functiondef(procedure_row.oid)
    FROM pg_proc procedure_row
    JOIN pg_namespace procedure_namespace
      ON procedure_namespace.oid = procedure_row.pronamespace
    WHERE procedure_namespace.nspname = 'public'
      AND procedure_row.proname = 'reject_security_event_mutation'
),
fingerprint_rows AS (
    SELECT concat_ws(
        '|',
        object_type,
        table_name,
        object_name,
        definition
    ) AS row_text
    FROM schema_objects
)
SELECT encode(
    sha256(
        convert_to(
            coalesce(
                string_agg(
                    row_text,
                    E'\n'
                    ORDER BY row_text COLLATE "C"
                ) || E'\n',
                ''
            ),
            'UTF8'
        )
    ),
    'hex'
) AS schema_fingerprint
FROM fingerprint_rows;
