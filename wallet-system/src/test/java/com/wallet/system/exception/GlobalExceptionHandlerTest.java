package com.wallet.system.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/wallet/transfer");
    }

    @Test
    void handleInvalidTransfer_returns400() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleInvalidTransfer(new InvalidTransferException("bad transfer"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getMessage()).isEqualTo("bad transfer");
    }

    @Test
    void handleAccessDenied_returns403() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleAccessDenied(new AccessDeniedException("denied"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void handleDataIntegrity_returns409() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleDataIntegrity(new DataIntegrityViolationException("dup key"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().getMessage()).contains("Duplicate request detected");
    }

    @Test
    void handleOptimisticLock_returns409() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException("Wallet", 1L), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void handleConcurrencyConflict_returns409() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleConcurrencyConflict(new ConcurrencyConflictException("retry exhausted"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().getMessage()).isEqualTo("retry exhausted");
    }

    @Test
    void handleGeneric_returns500AndHidesInternals() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleGeneric(new RuntimeException("some internal secret detail"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getMessage()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void handleNotFound_returns404() {
        ResponseEntity<com.wallet.system.dto.ErrorResponse> resp =
                handler.handleNotFound(new WalletNotFoundException("no wallet"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
