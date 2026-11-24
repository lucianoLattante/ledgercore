package io.github.lucianolattante.ledgercore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static io.github.lucianolattante.ledgercore.ledger.LedgerException.Reason.*;

@Service
public class LedgerService {
    private final LedgerStore store;

    LedgerService(LedgerStore store) {
        this.store = store;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult transfer(String actor, String key, TransferCommand command) {
        if (!store.claimRequest(actor, key)) {
            PostedTransfer existing = store.findPostedTransfer(actor, key);
            if (existing.reversesTransferId() != null
                    || !existing.debitAccountId().equals(command.debitAccountId())
                    || !existing.creditAccountId().equals(command.creditAccountId())
                    || !existing.currency().equals(command.currency())
                    || existing.amount().compareTo(command.amount()) != 0) {
                throw new LedgerException(IDEMPOTENCY_CONFLICT);
            }
            return new TransferResult(existing, true);
        }
        if (command.debitAccountId().equals(command.creditAccountId())) {
            throw new LedgerException(TRANSFER_TO_SAME_ACCOUNT);
        }
        return post(actor, key, command, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult reverse(String actor, String key, UUID transferId) {
        if (!store.claimRequest(actor, key)) {
            PostedTransfer existing = store.findPostedTransfer(actor, key);
            if (!transferId.equals(existing.reversesTransferId())) {
                throw new LedgerException(IDEMPOTENCY_CONFLICT);
            }
            return new TransferResult(existing, true);
        }
        var original = store.findReversibleTransfer(actor, transferId)
                .orElseThrow(() -> new LedgerException(TRANSFER_NOT_FOUND));
        var command = new TransferCommand(original.creditAccountId(), original.debitAccountId(),
                original.currency(), original.amount());
        return post(actor, key, command, transferId);
    }

    private TransferResult post(String actor, String key, TransferCommand command, UUID reversesTransferId) {
        var accounts = store.lockAccounts(command.debitAccountId(), command.creditAccountId());
        var debit = accounts.stream()
                .filter(account -> account.id().equals(command.debitAccountId()) && account.owner().equals(actor))
                .findFirst().orElseThrow(() -> new LedgerException(ACCOUNT_NOT_FOUND));
        var credit = accounts.stream()
                .filter(account -> account.id().equals(command.creditAccountId()))
                .findFirst().orElseThrow(() -> new LedgerException(ACCOUNT_NOT_FOUND));
        if (!debit.currency().equals(command.currency()) || !credit.currency().equals(command.currency())) {
            throw new LedgerException(CURRENCY_MISMATCH);
        }
        if (reversesTransferId != null && store.hasReversal(reversesTransferId)) {
            throw new LedgerException(TRANSFER_ALREADY_REVERSED);
        }

        // Read in a new statement after the locks. Its snapshot includes the writer that may have released them.
        if (balance(actor, debit.id()).amount().compareTo(command.amount()) < 0) {
            throw new LedgerException(INSUFFICIENT_FUNDS);
        }
        return new TransferResult(store.postTransfer(UUID.randomUUID(), actor, key, command, reversesTransferId), false);
    }

    public AccountBalance balance(String actor, UUID accountId) {
        return store.balance(actor, accountId).orElseThrow(() -> new LedgerException(ACCOUNT_NOT_FOUND));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MovementPage movements(String actor, UUID accountId, int limit, UUID before) {
        if (limit < 1 || limit > 100) throw new LedgerException(INVALID_PAGE_SIZE);
        if (!store.ownsAccount(actor, accountId)) throw new LedgerException(ACCOUNT_NOT_FOUND);
        var cursor = before == null ? null : store.movementCursor(accountId, before)
                .orElseThrow(() -> new LedgerException(TRANSFER_NOT_FOUND));
        var rows = store.movements(accountId, cursor, limit + 1);
        if (rows.size() <= limit) return new MovementPage(rows, null);
        return new MovementPage(List.copyOf(rows.subList(0, limit)), rows.get(limit - 1).transferId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Reconciliation reconcile(String actor, UUID accountId) {
        return store.reconciliation(actor, accountId)
                .orElseThrow(() -> new LedgerException(ACCOUNT_NOT_FOUND));
    }

    public record Movement(UUID transferId, Instant postedAt, String currency, BigDecimal delta,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversesTransferId) {}

    public record MovementPage(List<Movement> movements,
                               @JsonInclude(JsonInclude.Include.NON_NULL) UUID nextBefore) {}

    public record Reconciliation(UUID accountId, String currency, BigDecimal openingBalance,
                                 BigDecimal transferBalance, BigDecimal journalBalance,
                                 long transferCount, long journalEntryCount, boolean reconciled) {}

    public record TransferCommand(UUID debitAccountId, UUID creditAccountId, String currency, BigDecimal amount) {
        public TransferCommand {
            if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                    || (long) amount.precision() - amount.scale() > 17) {
                throw new LedgerException(INVALID_AMOUNT);
            }
        }
    }

    public record PostedTransfer(UUID id, UUID debitAccountId, UUID creditAccountId, String currency,
                                 BigDecimal amount, Instant postedAt,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversesTransferId) {}

    public record TransferResult(PostedTransfer transfer, boolean replayed) {}

    public record AccountBalance(UUID accountId, String currency, BigDecimal amount) {}
}
