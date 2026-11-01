package com.vpnservice.xui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Трафик и срок клиента, связывается с XuiClientEntry по email
@JsonIgnoreProperties(ignoreUnknown = true)
public record XuiClientStat(
        String email,
        boolean enable,
        long up,
        long down,
        long total,
        long expiryTime) {
}
