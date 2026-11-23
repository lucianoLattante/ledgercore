package io.github.lucianolattante.ledgercore;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "ledgercore.demo.alice-password=alice-integration-fixture",
        "ledgercore.demo.bob-password=bob-integration-fixture",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '10s'"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class LedgerCoreIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private UUID alice;
    private UUID bob;
    private UUID bobReserve;

    @BeforeEach
    void seed() {
        alice = account("alice", "ARS", "100.00");
        bob = account("bob", "ARS", "50.00");
        bobReserve = account("bob", "ARS", "0.00");
    }

    @Test
    void postsBalancedImmutableEntriesAndReplaysTheOriginalResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = transfer("alice", key, alice, bob, "ARS", "25.50");
        var replay = transfer("alice", key, alice, bob, "ARS", "25.5");
        var conflict = transfer("alice", key, alice, bob, "ARS", "26.00");

        assertThat(status(first)).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("false");
        assertThat(status(replay)).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(body(replay)).isEqualTo(body(first));
        assertProblem(conflict, 409, "idempotency_conflict");

        UUID transferId = UUID.fromString(body(first).get("id").asText());
        assertThat(jdbc.sql("SELECT delta FROM journal_entry WHERE transfer_id = :id ORDER BY delta")
                .param("id", transferId).query(BigDecimal.class).list())
                .containsExactly(new BigDecimal("-25.50"), new BigDecimal("25.50"));
        assertThatThrownBy(() -> jdbc.sql("UPDATE posted_transfer SET amount = 1 WHERE id = :id")
                .param("id", transferId).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM posted_transfer WHERE id = :id")
                .param("id", transferId).update()).isInstanceOf(DataAccessException.class);
        assertBalance("alice", alice, "74.50");
        assertBalance("bob", bob, "75.50");
    }

    @Test
    void sameKeyFromDifferentActorsAndOppositeTransfersBothSucceed() throws Exception {
        String key = UUID.randomUUID().toString();
        var results = race(
                () -> transfer("alice", key, alice, bob, "ARS", "20.00"),
                () -> transfer("bob", key, bob, alice, "ARS", "10.00"));

        assertThat(status(results.first())).isEqualTo(201);
        assertThat(status(results.second())).isEqualTo(201);
        assertThat(body(results.first()).get("id")).isNotEqualTo(body(results.second()).get("id"));
        assertBalance("alice", alice, "90.00");
        assertBalance("bob", bob, "60.00");
    }

    @Test
    void concurrentRetriesWithSameActorAndKeyPostOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        Callable<MvcResult> call = () -> transfer("alice", key, alice, bob, "ARS", "30.00");
        var results = race(call, call);

        assertThat(new int[]{status(results.first()), status(results.second())}).containsExactlyInAnyOrder(200, 201);
        assertThat(body(results.first())).isEqualTo(body(results.second()));
        assertBalance("alice", alice, "70.00");
        assertBalance("bob", bob, "80.00");
    }

    @Test
    void concurrentDebitsCannotOverdrawOrChangeTheTotal() throws Exception {
        var results = race(
                () -> transfer("alice", UUID.randomUUID().toString(), alice, bob, "ARS", "80.00"),
                () -> transfer("alice", UUID.randomUUID().toString(), alice, bobReserve, "ARS", "80.00"));

        assertThat(new int[]{status(results.first()), status(results.second())}).containsExactlyInAnyOrder(201, 409);
        assertProblem(status(results.first()) == 409 ? results.first() : results.second(), 409, "insufficient_funds");
        assertBalance("alice", alice, "20.00");
        assertThat(balance("bob", bob).add(balance("bob", bobReserve))).isEqualByComparingTo("130.00");
    }

    @Test
    void journalWriteFailureRollsBackBothEntriesAndReleasesTheRequestKey() throws Exception {
        String key = UUID.randomUUID().toString();
        jdbc.sql("""
                CREATE FUNCTION test_reject_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'synthetic write failure';
                END;
                $$
                """).update();
        try {
            jdbc.sql("""
                    CREATE TRIGGER test_reject_transfer AFTER INSERT ON posted_transfer
                    FOR EACH ROW EXECUTE FUNCTION test_reject_transfer()
                    """).update();
            try {
                assertProblem(transfer("alice", key, alice, bob, "ARS", "12.00"), 500, "database_error");
                assertBalance("alice", alice, "100.00");
                assertBalance("bob", bob, "50.00");
                assertThat(jdbc.sql("SELECT COUNT(*) FROM transfer_request WHERE actor = 'alice' AND request_key = :key")
                        .param("key", key).query(Long.class).single()).isZero();
            } finally {
                jdbc.sql("DROP TRIGGER test_reject_transfer ON posted_transfer").update();
            }
        } finally {
            jdbc.sql("DROP FUNCTION test_reject_transfer()").update();
        }
        assertThat(status(transfer("alice", key, alice, bob, "ARS", "12.00"))).isEqualTo(201);
        assertBalance("alice", alice, "88.00");
        assertBalance("bob", bob, "62.00");
    }

    @Test
    void rejectsAnonymousRequestsAndDoesNotRevealOtherActorsDebitAccounts() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(status(http.perform(get("/api/accounts/{id}", alice)).andReturn())).isEqualTo(401);
        assertThat(status(http.perform(get("/api/accounts/{id}", alice)
                .header("Authorization", basic("bob"))).andReturn())).isEqualTo(404);
        assertProblem(transfer("bob", key, alice, bob, "ARS", "1.00"), 404, "account_not_found");
        assertBalance("alice", alice, "100.00");
    }

    @Test
    void rejectsInvalidRequestsWithoutPostingEntries() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID foreign = account("bob", "USD", "50.00");
        assertProblem(transfer("alice", key, alice, foreign, "ARS", "1.00"), 422, "currency_mismatch");
        assertProblem(transfer("alice", key, alice, alice, "ARS", "1.00"), 422, "same_account");
        assertProblem(transfer("alice", key, alice, bob, "ARS", "1.001"), 400, "invalid_request");
        assertProblem(transfer("alice", key, alice, bob, "ARS", "1E+2147483647"), 400, "invalid_request");
        assertProblem(transfer("alice", "short", alice, bob, "ARS", "1.00"), 400, "invalid_request");
        assertBalance("alice", alice, "100.00");
    }

    private UUID account(String actor, String currency, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO ledger_account (id, owner_name, currency, opening_balance) VALUES (:id, :actor, :currency, :amount)")
                .param("id", id).param("actor", actor).param("currency", currency)
                .param("amount", new BigDecimal(amount)).update();
        return id;
    }

    private MvcResult transfer(String actor, String key, UUID from, UUID to, String currency, String amount)
            throws Exception {
        String request = """
                {"debitAccountId":"%s","creditAccountId":"%s","currency":"%s","amount":%s}
                """.formatted(from, to, currency, amount);
        return http.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(request)
                .header("Authorization", basic(actor)).header("Idempotency-Key", key)).andReturn();
    }

    private BigDecimal balance(String actor, UUID id) throws Exception {
        var result = http.perform(get("/api/accounts/{id}", id).header("Authorization", basic(actor))).andReturn();
        assertThat(status(result)).isEqualTo(200);
        return body(result).get("amount").decimalValue();
    }

    private void assertBalance(String actor, UUID id, String expected) throws Exception {
        assertThat(balance(actor, id)).isEqualByComparingTo(expected);
    }

    private void assertProblem(MvcResult result, int expectedStatus, String code) throws Exception {
        assertThat(status(result)).isEqualTo(expectedStatus);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String basic(String actor) {
        String fixture = actor + ":" + actor + "-integration-fixture";
        return "Basic " + Base64.getEncoder().encodeToString(fixture.getBytes(StandardCharsets.UTF_8));
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static RaceResult race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            var two = pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            return new RaceResult(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }

    private record RaceResult(MvcResult first, MvcResult second) {}
}
