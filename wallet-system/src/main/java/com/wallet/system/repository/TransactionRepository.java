package com.wallet.system.repository;

import com.wallet.system.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Page<Transaction> findByFromWallet_IdOrToWallet_IdOrderByCreatedAtDesc(
            Long fromWalletId, Long toWalletId, Pageable pageable);
}
