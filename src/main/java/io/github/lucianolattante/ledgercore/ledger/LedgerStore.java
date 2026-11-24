package io.github.lucianolattante.ledgercore.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.Movement;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.Reconciliation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.lucianolattante.ledgercore.ledger.LedgerService.AccountBalance;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.PostedTransfer;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.TransferCommand;

@Repository
class LedgerStore {
    private final JdbcClient jdbc;

    LedgerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean claimRequest(String actor, String key) {
        return jdbc.sql("""
                INSERT INTO transfer_request (actor, request_key) VALUES (:actor, :key)
                ON CONFLICT (actor, request_key) DO NOTHING
                """)
                .param("actor", actor).param("key", key).update() == 1;
    }

    List<Account> lockAccounts(UUID debit, UUID credit) {
        return jdbc.sql("""
                SELECT id, owner_name, currency FROM ledger_account
                WHERE id IN (:debit, :credit)
                ORDER BY id FOR NO KEY UPDATE
                """)
                .param("debit", debit).param("credit", credit)
                .query((rs, row) -> new Account(
                        rs.getObject("id", UUID.class), rs.getString("owner_name"), rs.getString("currency")))
                .list();
    }

    Optional<AccountBalance> balance(String actor, UUID accountId) {
        return jdbc.sql("""
                SELECT a.id, a.currency, a.opening_balance + COALESCE((
                    SELECT SUM(delta) FROM journal_entry WHERE account_id = a.id
                ), 0) AS balance
                FROM ledger_account a WHERE a.id = :account AND a.owner_name = :actor
                """)
                .param("account", accountId).param("actor", actor)
                .query((rs, row) -> new AccountBalance(rs.getObject("id", UUID.class),
                        rs.getString("currency"), rs.getBigDecimal("balance")))
                .optional();
    }

    boolean ownsAccount(String actor, UUID accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger_account WHERE id = :account AND owner_name = :actor)")
                .param("account", accountId).param("actor", actor).query(Boolean.class).single();
    }

    Optional<MovementCursor> movementCursor(UUID accountId, UUID transferId) {
        return jdbc.sql("""
                SELECT id, posted_at FROM posted_transfer
                WHERE id = :id AND (debit_account_id = :account OR credit_account_id = :account)
                """)
                .param("account", accountId).param("id", transferId)
                .query((rs, row) -> new MovementCursor(rs.getObject("id", UUID.class),
                        rs.getObject("posted_at", OffsetDateTime.class))).optional();
    }

    List<Movement> movements(UUID accountId, MovementCursor cursor, int limit) {
        String sql = """
                SELECT id, posted_at, currency,
                       CASE WHEN debit_account_id = :account THEN -amount ELSE amount END AS delta,
                       reverses_transfer_id
                FROM posted_transfer
                WHERE (debit_account_id = :account OR credit_account_id = :account)
                """;
        if (cursor != null) sql += " AND (posted_at, id) < (:beforeTime, :beforeId)";
        var statement = jdbc.sql(sql + " ORDER BY posted_at DESC, id DESC LIMIT :limit")
                .param("account", accountId).param("limit", limit);
        if (cursor != null) statement.param("beforeTime", cursor.postedAt()).param("beforeId", cursor.transferId());
        return statement.query((rs, row) -> new Movement(
                rs.getObject("id", UUID.class), rs.getObject("posted_at", OffsetDateTime.class).toInstant(),
                rs.getString("currency"), rs.getBigDecimal("delta"), rs.getObject("reverses_transfer_id", UUID.class)
        )).list();
    }

    Optional<Reconciliation> reconciliation(String actor, UUID accountId) {
        return jdbc.sql("""
                SELECT a.id, a.currency, a.opening_balance,
                       a.opening_balance + transfers.delta AS transfer_balance,
                       a.opening_balance + journal.delta AS journal_balance,
                       transfers.count AS transfer_count, journal.count AS journal_count
                FROM ledger_account a
                CROSS JOIN LATERAL (
                    SELECT count(*) AS count, COALESCE(SUM(CASE WHEN debit_account_id = a.id
                        THEN -amount ELSE amount END), 0) AS delta
                    FROM posted_transfer WHERE debit_account_id = a.id OR credit_account_id = a.id
                ) transfers
                CROSS JOIN LATERAL (
                    SELECT count(*) AS count, COALESCE(SUM(delta), 0) AS delta
                    FROM journal_entry WHERE account_id = a.id
                ) journal
                WHERE a.id = :account AND a.owner_name = :actor
                """)
                .param("account", accountId).param("actor", actor)
                .query((rs, row) -> {
                    BigDecimal transferBalance = rs.getBigDecimal("transfer_balance");
                    BigDecimal journalBalance = rs.getBigDecimal("journal_balance");
                    long transferCount = rs.getLong("transfer_count");
                    long journalCount = rs.getLong("journal_count");
                    return new Reconciliation(rs.getObject("id", UUID.class), rs.getString("currency"),
                            rs.getBigDecimal("opening_balance"), transferBalance, journalBalance,
                            transferCount, journalCount,
                            transferCount == journalCount && transferBalance.compareTo(journalBalance) == 0);
                }).optional();
    }

    record MovementCursor(UUID transferId, OffsetDateTime postedAt) {}

    PostedTransfer findPostedTransfer(String actor, String key) {
        return jdbc.sql("""
                SELECT id, debit_account_id, credit_account_id, currency, amount, posted_at, reverses_transfer_id
                FROM posted_transfer WHERE actor = :actor AND request_key = :key
                """)
                .param("actor", actor).param("key", key)
                .query((rs, row) -> postedTransfer(rs)).single();
    }

    Optional<PostedTransfer> findReversibleTransfer(String actor, UUID id) {
        return jdbc.sql("""
                SELECT t.id, t.debit_account_id, t.credit_account_id, t.currency, t.amount, t.posted_at,
                       t.reverses_transfer_id
                FROM posted_transfer t JOIN ledger_account credit ON credit.id = t.credit_account_id
                WHERE t.id = :id AND credit.owner_name = :actor AND t.reverses_transfer_id IS NULL
                """)
                .param("id", id).param("actor", actor)
                .query((rs, row) -> postedTransfer(rs)).optional();
    }

    boolean hasReversal(UUID transferId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM posted_transfer WHERE reverses_transfer_id = :id)")
                .param("id", transferId).query(Boolean.class).single();
    }

    PostedTransfer postTransfer(UUID id, String actor, String key, TransferCommand command, UUID reversesTransferId) {
        return jdbc.sql("""
                INSERT INTO posted_transfer (id, actor, request_key, debit_account_id, credit_account_id,
                                             currency, amount, reverses_transfer_id)
                VALUES (:id, :actor, :key, :debit, :credit, :currency, :amount, :original)
                RETURNING id, debit_account_id, credit_account_id, currency, amount, posted_at, reverses_transfer_id
                """)
                .param("id", id).param("actor", actor).param("key", key)
                .param("debit", command.debitAccountId()).param("credit", command.creditAccountId())
                .param("currency", command.currency()).param("amount", command.amount())
                .param("original", reversesTransferId)
                .query((rs, row) -> postedTransfer(rs)).single();
    }

    private static PostedTransfer postedTransfer(ResultSet rs) throws SQLException {
        return new PostedTransfer(rs.getObject("id", UUID.class), rs.getObject("debit_account_id", UUID.class),
                rs.getObject("credit_account_id", UUID.class), rs.getString("currency"),
                rs.getBigDecimal("amount"), rs.getObject("posted_at", OffsetDateTime.class).toInstant(),
                rs.getObject("reverses_transfer_id", UUID.class));
    }

    record Account(UUID id, String owner, String currency) {}
}
