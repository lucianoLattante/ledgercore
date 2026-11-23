CREATE TABLE ledger_account (
    id UUID PRIMARY KEY,
    owner_name TEXT NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    opening_balance NUMERIC(19,2) NOT NULL CHECK (opening_balance >= 0),
    UNIQUE (id, currency),
    UNIQUE (id, owner_name)
);

CREATE TABLE transfer_request (
    actor TEXT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (actor, request_key)
);

CREATE TABLE posted_transfer (
    id UUID PRIMARY KEY,
    actor TEXT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    posted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor, request_key),
    CHECK (debit_account_id <> credit_account_id),
    FOREIGN KEY (actor, request_key) REFERENCES transfer_request (actor, request_key),
    FOREIGN KEY (debit_account_id, actor) REFERENCES ledger_account (id, owner_name),
    FOREIGN KEY (debit_account_id, currency) REFERENCES ledger_account (id, currency),
    FOREIGN KEY (credit_account_id, currency) REFERENCES ledger_account (id, currency)
);

CREATE INDEX posted_transfer_debit_idx ON posted_transfer (debit_account_id);
CREATE INDEX posted_transfer_credit_idx ON posted_transfer (credit_account_id);

CREATE VIEW journal_entry AS
    SELECT id AS transfer_id, debit_account_id AS account_id, currency, -amount AS delta
    FROM posted_transfer
    UNION ALL
    SELECT id, credit_account_id, currency, amount
    FROM posted_transfer;

CREATE FUNCTION reject_posted_transfer_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'posted transfers are immutable';
END;
$$;

CREATE TRIGGER posted_transfer_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON posted_transfer
FOR EACH STATEMENT EXECUTE FUNCTION reject_posted_transfer_mutation();
