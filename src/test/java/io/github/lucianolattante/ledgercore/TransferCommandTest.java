package io.github.lucianolattante.ledgercore;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.lucianolattante.ledgercore.ledger.LedgerException;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.TransferCommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TransferCommandTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "0.001", "100000000000000000.00", "1E+2147483647"})
    void rejectsValuesOutsideTheLedgerAmountRange(String amount) {
        assertThatExceptionOfType(LedgerException.class)
                .isThrownBy(() -> command(amount))
                .satisfies(error -> assertThat(error.reason()).isEqualTo(LedgerException.Reason.INVALID_AMOUNT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "99999999999999999.99"})
    void acceptsPositiveAmountsThatFitWithoutRounding(String amount) {
        assertThat(command(amount).amount()).isEqualTo(new BigDecimal(amount));
    }

    private TransferCommand command(String amount) {
        return new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), "ARS", new BigDecimal(amount));
    }
}
