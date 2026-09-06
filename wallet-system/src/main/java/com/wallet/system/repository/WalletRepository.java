package com.wallet.system.repository;

import com.wallet.system.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * PESSIMISTIC_WRITE issues a "SELECT ... FOR UPDATE" at the DB level.
     * Any other transaction trying to read/lock the same row (with a lock
     * mode) blocks until this transaction commits or rolls back. This is
     * what makes a transfer's read-check-write sequence on a single wallet
     * atomic with respect to other concurrent transfers touching that wallet.
     *
     * Used together with pessimistic locking on BOTH wallets in a transfer,
     * always acquired in a fixed order (lowest id first) to prevent deadlocks
     * between two transfers going in opposite directions at the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
