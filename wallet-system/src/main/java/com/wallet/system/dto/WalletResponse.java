package com.wallet.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@AllArgsConstructor
public class WalletResponse {
    private Long walletId;
    private Long userId;
    private String ownerEmail;
    private BigDecimal balance;
    private Long version;
}
