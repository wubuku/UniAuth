SELECT 'noncanonical_contact_email'
WHERE EXISTS (
    SELECT 1
    FROM public.users
    WHERE email NOT LIKE '%@oauth.local'
      AND email NOT LIKE '%@web3.local'
      AND (
          email <> lower(btrim(email))
          OR length(email) > 254
          OR email ~ '[[:cntrl:]]'
      )
);

SELECT 'canonical_contact_email_conflict'
WHERE EXISTS (
    SELECT lower(btrim(email))
    FROM public.users
    WHERE email NOT LIKE '%@oauth.local'
      AND email NOT LIKE '%@web3.local'
    GROUP BY lower(btrim(email))
    HAVING count(*) > 1
);

SELECT 'canonical_local_username_conflict'
WHERE EXISTS (
    SELECT lower(btrim(local_username))
    FROM public.user_login_methods
    WHERE local_username IS NOT NULL
    GROUP BY lower(btrim(local_username))
    HAVING count(*) > 1
);
