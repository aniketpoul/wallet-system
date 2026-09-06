package com.wallet.system.controller;

import com.wallet.system.dto.AddMoneyRequest;
import com.wallet.system.dto.TransactionResponse;
import com.wallet.system.dto.TransferRequest;
import com.wallet.system.dto.WalletResponse;
import com.wallet.system.exception.InvalidTransferException;
import com.wallet.system.security.AuthenticatedUser;
import com.wallet.system.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * All endpoints here derive the acting wallet strictly from the authenticated
 * principal (AuthenticatedUser.userId), NEVER from a client-supplied id/body
 * field — this is what satisfies "Wallet ID must be derived from
 * authenticated user, not request data".
 */
@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class WalletController {

    private final WalletService walletService;

    @PostMapping("/add")
    public ResponseEntity<TransactionResponse> addMoney(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody AddMoneyRequest request) {

        validateIdempotencyKey(idempotencyKey);

        TransactionResponse response = walletService.addMoney(
                principal.getUserId(), idempotencyKey.trim(), request.getAmount());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        validateIdempotencyKey(idempotencyKey);

        TransactionResponse response = walletService.transfer(
                principal.getUserId(), idempotencyKey.trim(), request.getToEmail(), request.getAmount());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<WalletResponse> getMyWallet(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(walletService.getMyWallet(principal.getUserId()));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<TransactionResponse>> getMyTransactions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(walletService.getMyTransactions(principal.getUserId(), pageable));
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new InvalidTransferException("Idempotency-Key header must not be blank");
        }
        if (key.length() > 100) {
            throw new InvalidTransferException("Idempotency-Key header must not exceed 100 characters");
        }
    }
}
