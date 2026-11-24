CREATE FUNCTION reject_opening_balance_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Opening balance is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER ledger_account_opening_balance_immutable
BEFORE UPDATE OF opening_balance ON ledger_account
FOR EACH ROW WHEN (OLD.opening_balance IS DISTINCT FROM NEW.opening_balance)
EXECUTE FUNCTION reject_opening_balance_change();
