package com.vpnservice.controller;

import com.vpnservice.config.AggregatorProperties;
import com.vpnservice.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final AggregatorProperties properties;

    // Happ, v2rayTun и INCY понимают JSON-массив полных конфигов Xray: каждый элемент отдельный "сервер"
    @GetMapping(value = "/sub/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ObjectNode>> subscription(@PathVariable String token) {
        return subscriptionService.build(token)
                .map(content -> ResponseEntity.ok()
                        // base64: позволяет передать кириллицу и эмодзи в заголовке
                        .header("profile-title", "base64:" + Base64.getEncoder()
                                .encodeToString(properties.profileTitle().getBytes(StandardCharsets.UTF_8)))
                        .header("profile-update-interval", String.valueOf(properties.updateIntervalHours()))
                        .header("subscription-userinfo", content.userInfo().header())
                        .body(content.configs()))
                .orElse(ResponseEntity.notFound().build());
    }
}
