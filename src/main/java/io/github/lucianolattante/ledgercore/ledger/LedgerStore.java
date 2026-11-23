package io.github.lucianolattante.ledgercore.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

    PostedTransfer findPostedTransfer(String actor, String key) {
        return jdbc.sql("""
                SELECT id, debit_account_id, credit_account_id, currency, amount, posted_at
                FROM posted_transfer WHERE actor = :actor AND request_key = :key
                """)
                .param("actor", actor).param("key", key)
                .query((rs, row) -> postedTransfer(rs)).single();
    }

    PostedTransfer postTransfer(UUID id, String actor, String key, TransferCommand command) {
        return jdbc.sql("""
                INSERT INTO posted_transfer (id, actor, request_key, debit_account_id, credit_account_id,
                                             currency, amount)
                VALUES (:id, :actor, :key, :debit, :credit, :currency, :amount)
                RETURNING id, debit_account_id, credit_account_id, currency, amount, posted_at
                """)
                .param("id", id).param("actor", actor).param("key", key)
                .param("debit", command.debitAccountId()).param("credit", command.creditAccountId())
                .param("currency", command.currency()).param("amount", command.amount())
                .query((rs, row) -> postedTransfer(rs)).single();
    }

    private static PostedTransfer postedTransfer(ResultSet rs) throws SQLException {
        return new PostedTransfer(rs.getObject("id", UUID.class), rs.getObject("debit_account_id", UUID.class),
                rs.getObject("credit_account_id", UUID.class), rs.getString("currency"),
                rs.getBigDecimal("amount"), rs.getObject("posted_at", OffsetDateTime.class).toInstant());
    }

    record Account(UUID id, String owner, String currency) {}
}
