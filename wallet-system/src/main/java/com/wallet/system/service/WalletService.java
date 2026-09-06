package com.wallet.system.service;

import com.wallet.system.dto.TransactionResponse;
import com.wallet.system.dto.WalletResponse;
import com.wallet.system.entity.Transaction;
import com.wallet.system.entity.TransactionStatus;
import com.wallet.system.entity.TransactionType;
import com.wallet.system.entity.User;
import com.wallet.system.entity.Wallet;
import com.wallet.system.exception.InsufficientBalanceException;
import com.wallet.system.exception.InvalidTransferException;
import com.wallet.system.exception.UserNotFoundException;
import com.wallet.system.exception.WalletNotFoundException;
import com.wallet.system.repository.TransactionRepository;
import com.wallet.system.repository.UserRepository;
import com.wallet.system.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    // ---------------------------------------------------------------
    // Read operations
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public WalletResponse getMyWallet(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for current user"));
        return toWalletResponse(wallet);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getMyTransactions(Long userId, Pageable pageable) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for current user"));
        return transactionRepository
                .findByFromWallet_IdOrToWallet_IdOrderByCreatedAtDesc(wallet.getId(), wallet.getId(), pageable)
                .map(this::toTransactionResponse);
    }

    // ---------------------------------------------------------------
    // ADD MONEY
    // ---------------------------------------------------------------

    /**
     * Idempotency strategy (see README "Idempotency" section for full detail):
     *  1. Fast path: if a Transaction already exists for this key, return it
     *     unchanged instead of reprocessing (handles retries / double clicks /
     *     network-retry duplicates that are NOT concurrent with each other).
     *  2. Slow path / true race: two requests with the same key arrive at the
     *     same instant, both pass step 1, and both try to INSERT a Transaction
     *     row with that key. The DB-level UNIQUE constraint on
     *     transactions.idempotency_key allows only one INSERT to succeed; the
     *     other throws DataIntegrityViolationException, which rolls back that
     *     request's balance change entirely (see GlobalExceptionHandler) and
     *     returns HTTP 409 telling the caller to re-fetch by key.
     *
     * Concurrency strategy: the wallet row is fetched with PESSIMISTIC_WRITE
     * (SELECT ... FOR UPDATE), so a concurrent transfer or add-money touching
     * the same wallet blocks until this one commits — no lost updates.
     */
    @Transactional
    public TransactionResponse addMoney(Long userId, String idempotencyKey, BigDecimal amount) {
        Optional<TransactionResponse> replay = replayIfExists(idempotencyKey, userId);
        if (replay.isPresent()) {
            return replay.get();
        }

        Wallet walletRef = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for current user"));

        // Acquire the row lock before mutating the balance.
        Wallet lockedWallet = walletRepository.findByIdForUpdate(walletRef.getId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for current user"));

        lockedWallet.setBalance(lockedWallet.getBalance().add(amount));
        walletRepository.save(lockedWallet);

        Transaction transaction = Transaction.builder()
                .fromWallet(null)
                .toWallet(lockedWallet)
                .amount(amount)
                .type(TransactionType.ADD)
                .status(TransactionStatus.SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        transaction = transactionRepository.save(transaction);

        return toTransactionResponse(transaction);
    }

    // ---------------------------------------------------------------
    // TRANSFER
    // ---------------------------------------------------------------

    /**
     * Atomic, deadlock-safe transfer between two wallets.
     *
     * Atomicity: the whole method runs in one DB transaction. Either both the
     * debit and the credit are committed, or neither is (any exception rolls
     * the transaction back, except InsufficientBalanceException — see
     * noRollbackFor below, which lets us persist a FAILED audit record).
     *
     * Deadlock avoidance: naive code would lock "sender then receiver". If
     * user A transfers to B at the same time B transfers to A, A's request
     * locks A-then-B while B's request locks B-then-A -> classic deadlock.
     * We always acquire locks in ascending wallet-id order regardless of
     * transfer direction, so every concurrent transfer touching these two
     * wallets requests the locks in the same global order.
     */
    @Transactional(noRollbackFor = InsufficientBalanceException.class)
    public TransactionResponse transfer(Long userId, String idempotencyKey, String toEmail, BigDecimal amount) {
        Optional<TransactionResponse> replay = replayIfExists(idempotencyKey, userId);
        if (replay.isPresent()) {
            return replay.get();
        }

        Wallet senderRef = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Sender wallet not found"));

        User receiverUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new UserNotFoundException("Recipient not found: " + toEmail));

        if (Objects.equals(receiverUser.getId(), userId)) {
            throw new InvalidTransferException("Cannot transfer money to your own wallet");
        }

        Wallet receiverRef = walletRepository.findByUserId(receiverUser.getId())
                .orElseThrow(() -> new WalletNotFoundException("Recipient wallet not found"));

        Long firstLockId = Math.min(senderRef.getId(), receiverRef.getId());
        Long secondLockId = Math.max(senderRef.getId(), receiverRef.getId());

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));

        Wallet senderWallet = firstLocked.getId().equals(senderRef.getId()) ? firstLocked : secondLocked;
        Wallet receiverWallet = firstLocked.getId().equals(receiverRef.getId()) ? firstLocked : secondLocked;

        if (senderWallet.getBalance().compareTo(amount) < 0) {
            // Persist the failed attempt under this idempotency key so a retry
            // with the same key returns the same failure instead of re-checking
            // (and so the client can't "retry into success" by resubmitting).
            Transaction failed = Transaction.builder()
                    .fromWallet(senderWallet)
                    .toWallet(receiverWallet)
                    .amount(amount)
                    .type(TransactionType.TRANSFER)
                    .status(TransactionStatus.FAILED)
                    .idempotencyKey(idempotencyKey)
                    .failureReason("INSUFFICIENT_BALANCE")
                    .build();
            transactionRepository.save(failed);
            throw new InsufficientBalanceException("Insufficient balance to complete transfer");
        }

        senderWallet.setBalance(senderWallet.getBalance().subtract(amount));
        receiverWallet.setBalance(receiverWallet.getBalance().add(amount));
        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        Transaction transaction = Transaction.builder()
                .fromWallet(senderWallet)
                .toWallet(receiverWallet)
                .amount(amount)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        transaction = transactionRepository.save(transaction);

        return toTransactionResponse(transaction);
    }

    // ---------------------------------------------------------------
    // Admin
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<WalletResponse> getAllWallets(Pageable pageable) {
        return walletRepository.findAll(pageable).map(this::toWalletResponse);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getAllTransactions(Pageable pageable) {
        return transactionRepository.findAll(pageable).map(this::toTransactionResponse);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private Optional<TransactionResponse> replayIfExists(String idempotencyKey, Long requestingUserId) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .filter(tx -> belongsToUser(tx, requestingUserId))
                .map(this::toTransactionResponse);
    }

    private boolean belongsToUser(Transaction tx, Long userId) {
        boolean isSender = tx.getFromWallet() != null && tx.getFromWallet().getUser().getId().equals(userId);
        boolean isReceiver = tx.getToWallet() != null && tx.getToWallet().getUser().getId().equals(userId);
        return isSender || isReceiver;
    }

    private WalletResponse toWalletResponse(Wallet wallet) {
        return WalletResponse.builder()
                .walletId(wallet.getId())
                .userId(wallet.getUser().getId())
                .ownerEmail(wallet.getUser().getEmail())
                .balance(wallet.getBalance())
                .version(wallet.getVersion())
                .build();
    }

    private TransactionResponse toTransactionResponse(Transaction tx) {
        return TransactionResponse.builder()
                .id(tx.getId())
                .fromWalletId(tx.getFromWallet() != null ? tx.getFromWallet().getId() : null)
                .toWalletId(tx.getToWallet() != null ? tx.getToWallet().getId() : null)
                .amount(tx.getAmount())
                .type(tx.getType().name())
                .status(tx.getStatus().name())
                .idempotencyKey(tx.getIdempotencyKey())
                .failureReason(tx.getFailureReason())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
