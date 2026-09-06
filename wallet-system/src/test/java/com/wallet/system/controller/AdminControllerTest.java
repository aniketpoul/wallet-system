package com.wallet.system.controller;

import com.wallet.system.dto.TransactionResponse;
import com.wallet.system.dto.WalletResponse;
import com.wallet.system.security.AuthenticatedUser;
import com.wallet.system.security.CustomUserDetailsService;
import com.wallet.system.security.JwtAuthenticationFilter;
import com.wallet.system.security.JwtUtil;
import com.wallet.system.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private WalletService walletService;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private CustomUserDetailsService customUserDetailsService;
    @MockBean private JwtUtil jwtUtil;

    private Authentication asAdmin() {
        AuthenticatedUser principal = new AuthenticatedUser(1L, "admin@wallet.system", "hashed", "ADMIN");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void getAllWallets_returnsPagedWallets() throws Exception {
        WalletResponse w = WalletResponse.builder()
                .walletId(10L).userId(1L).ownerEmail("alice@example.com")
                .balance(new BigDecimal("1000.00")).version(0L).build();
        when(walletService.getAllWallets(any())).thenReturn(new PageImpl<>(List.of(w)));

        mockMvc.perform(get("/admin/wallets")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ownerEmail").value("alice@example.com"));
    }

    @Test
    void getAllTransactions_returnsPagedTransactions() throws Exception {
        TransactionResponse tx = TransactionResponse.builder()
                .id(1L).toWalletId(10L).amount(BigDecimal.TEN)
                .type("ADD").status("SUCCESS").idempotencyKey("k1")
                .createdAt(LocalDateTime.now()).build();
        when(walletService.getAllTransactions(any())).thenReturn(new PageImpl<>(List.of(tx)));

        mockMvc.perform(get("/admin/transactions")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idempotencyKey").value("k1"));
    }
}
