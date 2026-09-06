package com.wallet.system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.system.dto.AddMoneyRequest;
import com.wallet.system.dto.TransactionResponse;
import com.wallet.system.dto.TransferRequest;
import com.wallet.system.dto.WalletResponse;
import com.wallet.system.exception.InsufficientBalanceException;
import com.wallet.system.exception.UserNotFoundException;
import com.wallet.system.security.AuthenticatedUser;
import com.wallet.system.security.CustomUserDetailsService;
import com.wallet.system.security.JwtAuthenticationFilter;
import com.wallet.system.security.JwtUtil;
import com.wallet.system.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
@AutoConfigureMockMvc(addFilters = false)
class WalletControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private WalletService walletService;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private CustomUserDetailsService customUserDetailsService;
    @MockBean private JwtUtil jwtUtil;

    private Authentication asUser;

    @BeforeEach
    void setUp() {
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice@example.com", "hashed", "USER");
        asUser = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void addMoney_withValidRequestAndKey_returns201() throws Exception {
        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(new BigDecimal("500.00"));

        TransactionResponse response = TransactionResponse.builder()
                .id(1L).toWalletId(10L).amount(new BigDecimal("500.00"))
                .type("ADD").status("SUCCESS").idempotencyKey("key-1")
                .createdAt(LocalDateTime.now()).build();

        when(walletService.addMoney(eq(1L), eq("key-1"), any(BigDecimal.class))).thenReturn(response);

        mockMvc.perform(post("/wallet/add")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void addMoney_withMissingIdempotencyKeyHeader_returns400() throws Exception {
        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(new BigDecimal("500.00"));

        mockMvc.perform(post("/wallet/add")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addMoney_withZeroAmount_returns400() throws Exception {
        AddMoneyRequest request = new AddMoneyRequest();
        request.setAmount(BigDecimal.ZERO);

        mockMvc.perform(post("/wallet/add")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transfer_withValidRequest_returns201() throws Exception {
        TransferRequest request = new TransferRequest();
        request.setToEmail("bob@example.com");
        request.setAmount(new BigDecimal("100.00"));

        TransactionResponse response = TransactionResponse.builder()
                .id(2L).fromWalletId(10L).toWalletId(20L).amount(new BigDecimal("100.00"))
                .type("TRANSFER").status("SUCCESS").idempotencyKey("key-2")
                .createdAt(LocalDateTime.now()).build();

        when(walletService.transfer(eq(1L), eq("key-2"), eq("bob@example.com"), any(BigDecimal.class)))
                .thenReturn(response);

        mockMvc.perform(post("/wallet/transfer")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser))
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromWalletId").value(10))
                .andExpect(jsonPath("$.toWalletId").value(20));
    }

    @Test
    void transfer_toUnknownRecipient_returns404() throws Exception {
        TransferRequest request = new TransferRequest();
        request.setToEmail("ghost@example.com");
        request.setAmount(new BigDecimal("100.00"));

        when(walletService.transfer(eq(1L), anyString(), eq("ghost@example.com"), any(BigDecimal.class)))
                .thenThrow(new UserNotFoundException("Recipient not found: ghost@example.com"));

        mockMvc.perform(post("/wallet/transfer")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser))
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void transfer_insufficientBalance_returns422() throws Exception {
        TransferRequest request = new TransferRequest();
        request.setToEmail("bob@example.com");
        request.setAmount(new BigDecimal("999999.00"));

        when(walletService.transfer(eq(1L), anyString(), eq("bob@example.com"), any(BigDecimal.class)))
                .thenThrow(new InsufficientBalanceException("Insufficient balance to complete transfer"));

        mockMvc.perform(post("/wallet/transfer")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser))
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getMyWallet_returnsOwnWallet() throws Exception {
        WalletResponse response = WalletResponse.builder()
                .walletId(10L).userId(1L).ownerEmail("alice@example.com")
                .balance(new BigDecimal("1000.00")).version(0L).build();
        when(walletService.getMyWallet(1L)).thenReturn(response);

        mockMvc.perform(get("/wallet").with(SecurityMockMvcRequestPostProcessors.authentication(asUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00));
    }

    @Test
    void getMyTransactions_returnsPagedHistory() throws Exception {
        TransactionResponse tx = TransactionResponse.builder()
                .id(1L).toWalletId(10L).amount(BigDecimal.TEN)
                .type("ADD").status("SUCCESS").idempotencyKey("k1")
                .createdAt(LocalDateTime.now()).build();
        when(walletService.getMyTransactions(eq(1L), any())).thenReturn(new PageImpl<>(List.of(tx)));

        mockMvc.perform(get("/wallet/transactions")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idempotencyKey").value("k1"));
    }
}
