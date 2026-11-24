package io.github.lucianolattante.ledgercore.ledger;

public final class LedgerException extends RuntimeException {
    public enum Reason {
        ACCOUNT_NOT_FOUND,
        TRANSFER_NOT_FOUND,
        TRANSFER_ALREADY_REVERSED,
        TRANSFER_TO_SAME_ACCOUNT,
        CURRENCY_MISMATCH,
        INVALID_PAGE_SIZE,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        IDEMPOTENCY_CONFLICT
    }

    private final Reason reason;

    public LedgerException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
