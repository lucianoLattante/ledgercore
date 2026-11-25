GRANT SELECT ON public.ledger_account, public.transfer_request, public.posted_transfer, public.journal_entry
    TO ledgercore_runtime;
GRANT INSERT ON public.transfer_request, public.posted_transfer TO ledgercore_runtime;

-- PostgreSQL requires UPDATE on at least one column for SELECT ... FOR NO KEY UPDATE.
-- The V3 trigger rejects changes to opening_balance.
GRANT UPDATE (opening_balance) ON public.ledger_account TO ledgercore_runtime;
