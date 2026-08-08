-- Bind every active Web3 nonce to the exact SIWE message issued by the server.
-- Existing pre-V5 challenges cannot be reconstructed safely, so they are
-- invalidated at this security boundary before the column becomes mandatory.

DELETE FROM public.web3_nonces;

ALTER TABLE public.web3_nonces
    ADD COLUMN message text;

ALTER TABLE public.web3_nonces
    ALTER COLUMN message SET NOT NULL;
