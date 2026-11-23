package io.github.lucianolattante.ledgercore.api;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.lucianolattante.ledgercore.ledger.LedgerService;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.AccountBalance;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.PostedTransfer;
import io.github.lucianolattante.ledgercore.ledger.LedgerService.TransferCommand;

@RestController
@RequestMapping("/api")
class LedgerController {
    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping(path = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PostedTransfer> transfer(
            Principal principal,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._-]{8,128}") String key,
            @Valid @RequestBody TransferRequest request) {
        var command = new TransferCommand(request.debitAccountId(), request.creditAccountId(),
                request.currency(), request.amount());
        return postingResponse(ledger.transfer(principal.getName(), key, command));
    }

    @PostMapping("/transfers/{transferId}/reversal")
    ResponseEntity<PostedTransfer> reverse(
            Principal principal, @PathVariable UUID transferId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._-]{8,128}") String key) {
        return postingResponse(ledger.reverse(principal.getName(), key, transferId));
    }

    private ResponseEntity<PostedTransfer> postingResponse(LedgerService.TransferResult result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.transfer());
    }

    @GetMapping("/accounts/{accountId}")
    AccountBalance balance(Principal principal, @PathVariable UUID accountId) {
        return ledger.balance(principal.getName(), accountId);
    }

    record TransferRequest(
            @NotNull UUID debitAccountId,
            @NotNull UUID creditAccountId,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {}
}
