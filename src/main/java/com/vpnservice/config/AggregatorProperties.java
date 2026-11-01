package com.vpnservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("aggregator")
public record AggregatorProperties(
        // Название подписки в клиенте
        @DefaultValue("VPN") String profileTitle,
        // Как часто клиент сам обновляет подписку
        @DefaultValue("12") int updateIntervalHours,
        // uTLS-отпечаток, если панель его не задала (TLS навязан через externalProxy)
        @DefaultValue("chrome") String defaultFingerprint,
        // Внешний адрес агрегатора для ссылок в админке, например https://chad.whiteman.pics.
        // Не задан: берётся адрес, по которому открыта админка
        String publicUrl) {
}
