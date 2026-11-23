package io.github.lucianolattante.ledgercore.api;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.github.lucianolattante.ledgercore.ledger.LedgerException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(LedgerException.class)
    ProblemDetail ledgerError(LedgerException error) {
        return switch (error.reason()) {
            case ACCOUNT_NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "account_not_found",
                    "The requested account is unavailable.");
            case TRANSFER_TO_SAME_ACCOUNT -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "same_account",
                    "Debit and credit accounts must differ.");
            case CURRENCY_MISMATCH -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "currency_mismatch",
                    "Both accounts must match the requested currency.");
            case INVALID_AMOUNT -> invalidRequest();
            case INSUFFICIENT_FUNDS -> problem(HttpStatus.CONFLICT, "insufficient_funds",
                    "The debit account has insufficient funds.");
            case IDEMPOTENCY_CONFLICT -> problem(HttpStatus.CONFLICT, "idempotency_conflict",
                    "The request key already identifies a different transfer.");
        };
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request fields and headers.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail databaseError() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "database_error", "Unable to complete the ledger request.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
