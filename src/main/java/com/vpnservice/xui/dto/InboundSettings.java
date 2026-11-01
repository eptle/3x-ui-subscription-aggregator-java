package com.vpnservice.xui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// Содержимое строки XuiInbound.settings
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundSettings(
        List<XuiClientEntry> clients,
        String encryption,  // vless: клиентская часть VLESS Encryption, обычно "none"
        String method,      // shadowsocks
        String password) {  // shadowsocks 2022: серверный ключ

    public InboundSettings {
        // у socks/http/wireguard вместо clients другие поля
        clients = clients == null ? List.of() : clients;
    }
}
