package com.vpnservice.controller;

import com.vpnservice.config.AggregatorProperties;
import com.vpnservice.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final AggregatorProperties properties;

    // Happ, v2rayTun и INCY понимают JSON-массив полных конфигов Xray: каждый элемент отдельный "сервер".
    // v2rayNG и Throne его не понимают, им отдаём base64-список vless:// (только reality, без CDN).
    // format=json|links перекрывает определение по User-Agent: удобно проверять из браузера
    @GetMapping("/sub/{token}")
    public ResponseEntity<?> subscription(@PathVariable String token,
                                          @RequestHeader(value = HttpHeaders.USER_AGENT, defaultValue = "") String userAgent,
                                          @RequestParam(required = false) String format) {
        return subscriptionService.build(token)
                .map(content -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                            // base64: позволяет передать кириллицу и эмодзи в заголовке
                            .header("profile-title", "base64:" + base64(properties.profileTitle()))
                            .header("profile-update-interval", String.valueOf(properties.updateIntervalHours()))
                            .header("subscription-userinfo", content.userInfo().header());
                    return wantsLinks(userAgent, format)
                            ? response.contentType(MediaType.TEXT_PLAIN).body((Object) base64(String.join("\n", content.links())))
                            : response.contentType(MediaType.APPLICATION_JSON).body((Object) content.configs());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private boolean wantsLinks(String userAgent, String format) {
        if (format != null) {
            return "links".equalsIgnoreCase(format);
        }
        String agent = userAgent.toLowerCase(Locale.ROOT);
        return properties.linkClients().stream().anyMatch(client -> agent.contains(client.toLowerCase(Locale.ROOT)));
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
