package com.wallet.system.service;

import com.wallet.system.dto.TransactionResponse;
import com.wallet.system.dto.WalletResponse;
import com.wallet.system.entity.*;
import com.wallet.system.exception.InsufficientBalanceException;
import com.wallet.system.exception.InvalidTransferException;
import com.wallet.system.exception.UserNotFoundException;
import com.wallet.system.exception.WalletNotFoundException;
import com.wallet.system.repository.TransactionRepository;
import com.wallet.system.repository.UserRepository;
import com.wallet.system.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionRepository transactionRepository;

    @InjectMocks
    private WalletService walletService;

    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setUp() {
        alice = User.builder().id(1L).email("alice@example.com").role(Role.USER).build();
        bob = User.builder().id(2L).email("bob@example.com").role(Role.USER).build();

        aliceWallet = Wallet.builder().id(10L).user(alice).balance(new BigDecimal("1000.00")).version(0L).build();
        bobWallet = Wallet.builder().id(20L).user(bob).balance(new BigDecimal("200.00")).version(0L).build();
    }

    // -----------------------------------------------------------------
    // getMyWallet / getMyTransactions
    // -----------------------------------------------------------------

    @Test
    void getMyWallet_returnsWalletOfAuthenticatedUser() {
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));

        WalletResponse response = walletService.getMyWallet(1L);

        assertThat(response.getWalletId()).isEqualTo(10L);
        assertThat(response.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void getMyWallet_whenWalletMissing_throwsWalletNotFoundException() {
        when(walletRepository.findByUserId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getMyWallet(99L))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void getMyTransactions_returnsPagedHistoryForOwnWallet() {
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));
        Transaction tx = Transaction.builder()
                .id(1L).toWallet(aliceWallet).amount(BigDecimal.TEN)
                .type(TransactionType.ADD).status(TransactionStatus.SUCCESS)
                .idempotencyKey("k1").build();
        Pageable pageable = PageRequest.of(0, 20);
        when(transactionRepository.findByFromWallet_IdOrToWallet_IdOrderByCreatedAtDesc(10L, 10L, pageable))
                .thenReturn(new PageImpl<>(List.of(tx)));

        Page<TransactionResponse> result = walletService.getMyTransactions(1L, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getIdempotencyKey()).isEqualTo("k1");
    }

    // -----------------------------------------------------------------
    // addMoney — idempotency
    // -----------------------------------------------------------------

    @Test
    void addMoney_firstRequest_creditsWalletAndCreatesTransaction() {
        when(transactionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(aliceWallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(100L);
            t.setCreatedAt(java.time.LocalDateTime.now());
            return t;
        });

        TransactionResponse response = walletService.addMoney(1L, "key-1", new BigDecimal("500.00"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAmount()).isEqualByComparingTo("500.00");
        assertThat(aliceWallet.getBalance()).isEqualByComparingTo("1500.00");

        // Confirms the row was locked (SELECT ... FOR UPDATE) before mutating balance.
        verify(walletRepository, times(1)).findByIdForUpdate(10L);
    }

    @Test
    void addMoney_duplicateIdempotencyKey_returnsExistingResultWithoutReprocessing() {
        Transaction existing = Transaction.builder()
                .id(55L).toWallet(aliceWallet).amount(new BigDecimal("500.00"))
                .type(TransactionType.ADD).status(TransactionStatus.SUCCESS)
                .idempotencyKey("key-1").createdAt(java.time.LocalDateTime.now()).build();
        when(transactionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        TransactionResponse response = walletService.addMoney(1L, "key-1", new BigDecimal("500.00"));

        assertThat(response.getId()).isEqualTo(55L);
        // Balance must NOT be touched a second time.
        verify(walletRepository, never()).findByIdForUpdate(any());
        verify(walletRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void addMoney_whenWalletMissing_throwsWalletNotFoundException() {
        when(transactionRepository.findByIdempotencyKey("key-x")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.addMoney(1L, "key-x", BigDecimal.TEN))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // -----------------------------------------------------------------
    // transfer — happy path, lock ordering, idempotency, failure cases
    // -----------------------------------------------------------------

    @Test
    void transfer_sufficientBalance_debitsSenderAndCreditsReceiverAtomically() {
        when(transactionRepository.findByIdempotencyKey("tx-1")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(bobWallet));
        // sender id=10 < receiver id=20, so lock order is (10 then 20)
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(aliceWallet));
        when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(bobWallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(200L);
            t.setCreatedAt(java.time.LocalDateTime.now());
            return t;
        });

        TransactionResponse response = walletService.transfer(1L, "tx-1", "bob@example.com", new BigDecimal("300.00"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(aliceWallet.getBalance()).isEqualByComparingTo("700.00");
        assertThat(bobWallet.getBalance()).isEqualByComparingTo("500.00");

        // Locks must always be acquired lowest-id-first regardless of direction.
        var inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(10L);
        inOrder.verify(walletRepository).findByIdForUpdate(20L);
    }

    @Test
    void transfer_locksAscendingByWalletId_evenWhenReceiverHasSmallerId() {
        // Now Bob (wallet 20) is NOT smaller; flip so sender has the larger id.
        Wallet aliceHighId = Wallet.builder().id(50L).user(alice).balance(new BigDecimal("1000")).version(0L).build();
        Wallet bobLowId = Wallet.builder().id(5L).user(bob).balance(new BigDecimal("200")).version(0L).build();

        when(transactionRepository.findByIdempotencyKey("tx-2")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceHighId));
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(bobLowId));
        when(walletRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(bobLowId));
        when(walletRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(aliceHighId));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(201L);
            t.setCreatedAt(java.time.LocalDateTime.now());
            return t;
        });

        walletService.transfer(1L, "tx-2", "bob@example.com", new BigDecimal("100.00"));

        // Even though sender's wallet id (50) > receiver's wallet id (5),
        // the lower id must always be locked first to prevent deadlocks.
        var inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(5L);
        inOrder.verify(walletRepository).findByIdForUpdate(50L);
    }

    @Test
    void transfer_insufficientBalance_savesFailedTransactionAndThrows() {
        when(transactionRepository.findByIdempotencyKey("tx-3")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        when(walletRepository.findByUserId(2L)).thenReturn(Optional.of(bobWallet));
        when(walletRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(aliceWallet));
        when(walletRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(bobWallet));

        BigDecimal tooMuch = new BigDecimal("999999.00");

        assertThatThrownBy(() -> walletService.transfer(1L, "tx-3", "bob@example.com", tooMuch))
                .isInstanceOf(InsufficientBalanceException.class);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(captor.getValue().getFailureReason()).isEqualTo("INSUFFICIENT_BALANCE");

        // Balances must remain unchanged.
        assertThat(aliceWallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(bobWallet.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void transfer_toSelf_throwsInvalidTransferException() {
        when(transactionRepository.findByIdempotencyKey("tx-4")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        assertThatThrownBy(() -> walletService.transfer(1L, "tx-4", "alice@example.com", BigDecimal.TEN))
                .isInstanceOf(InvalidTransferException.class);

        verify(walletRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void transfer_toUnknownRecipient_throwsUserNotFoundException() {
        when(transactionRepository.findByIdempotencyKey("tx-5")).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(aliceWallet));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.transfer(1L, "tx-5", "ghost@example.com", BigDecimal.TEN))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void transfer_duplicateIdempotencyKey_returnsExistingResultWithoutTouchingBalances() {
        Transaction existing = Transaction.builder()
                .id(300L).fromWallet(aliceWallet).toWallet(bobWallet)
                .amount(new BigDecimal("300.00")).type(TransactionType.TRANSFER)
                .status(TransactionStatus.SUCCESS).idempotencyKey("tx-1")
                .createdAt(java.time.LocalDateTime.now()).build();
        when(transactionRepository.findByIdempotencyKey("tx-1")).thenReturn(Optional.of(existing));

        TransactionResponse response = walletService.transfer(1L, "tx-1", "bob@example.com", new BigDecimal("300.00"));

        assertThat(response.getId()).isEqualTo(300L);
        verify(walletRepository, never()).findByIdForUpdate(any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void transfer_idempotencyKeyBelongingToDifferentUser_isNotReplayed() {
        // Transaction exists but neither leg belongs to userId=99 -> must NOT be treated as a replay.
        Transaction existing = Transaction.builder()
                .id(300L).fromWallet(aliceWallet).toWallet(bobWallet)
                .amount(new BigDecimal("300.00")).type(TransactionType.TRANSFER)
                .status(TransactionStatus.SUCCESS).idempotencyKey("shared-key")
                .createdAt(java.time.LocalDateTime.now()).build();
        when(transactionRepository.findByIdempotencyKey("shared-key")).thenReturn(Optional.of(existing));
        when(walletRepository.findByUserId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.transfer(99L, "shared-key", "bob@example.com", BigDecimal.TEN))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // -----------------------------------------------------------------
    // Admin
    // -----------------------------------------------------------------

    @Test
    void getAllWallets_returnsPagedWallets() {
        Pageable pageable = PageRequest.of(0, 20);
        when(walletRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(aliceWallet, bobWallet)));

        Page<WalletResponse> result = walletService.getAllWallets(pageable);

        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void getAllTransactions_returnsPagedTransactions() {
        Pageable pageable = PageRequest.of(0, 20);
        Transaction tx = Transaction.builder()
                .id(1L).toWallet(aliceWallet).amount(BigDecimal.TEN)
                .type(TransactionType.ADD).status(TransactionStatus.SUCCESS)
                .idempotencyKey("k1").createdAt(java.time.LocalDateTime.now()).build();
        when(transactionRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(tx)));

        Page<TransactionResponse> result = walletService.getAllTransactions(pageable);

        assertThat(result.getContent()).hasSize(1);
    }
}
