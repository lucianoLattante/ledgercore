ALTER TABLE posted_transfer
    ADD COLUMN reverses_transfer_id UUID,
    ADD CONSTRAINT posted_transfer_reversal_original
        FOREIGN KEY (reverses_transfer_id) REFERENCES posted_transfer (id),
    ADD CONSTRAINT posted_transfer_reversal_unique UNIQUE (reverses_transfer_id),
    ADD CONSTRAINT posted_transfer_reversal_not_self CHECK (id <> reverses_transfer_id);
