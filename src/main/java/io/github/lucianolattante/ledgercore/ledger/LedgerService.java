package io.github.lucianolattante.ledgercore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
