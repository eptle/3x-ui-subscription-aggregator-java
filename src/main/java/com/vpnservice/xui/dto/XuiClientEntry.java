package com.vpnservice.xui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Клиент из settings.clients. Набор полей зависит от протокола inbound'а
@JsonIgnoreProperties(ignoreUnknown = true)
public record XuiClientEntry(
        String id,          // vless, vmess
        String password,    // trojan, shadowsocks
        String email,
        String subId,
        String flow,        // vless
        String security,    // vmess
        String method,      // shadowsocks
        boolean enable,
        long expiryTime,
        long totalGB,
        int limitIp) {
}
