package com.vpnservice.xray;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// Готовый outbound -> ссылка vless:// для клиентов, которые не понимают JSON-конфиги (v2rayNG, Throne).
// Параметры как в ссылках 3x-ui, чтобы клиенты разбирали их так же
@Component
public class ShareLinkFactory {

    private final JsonMapper jsonMapper;

    public ShareLinkFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // Только vless + reality: CDN-выходы идут через TLS и в список ссылок не попадают
    public Optional<String> vlessReality(JsonNode outbound, String remark) {
        JsonNode stream = outbound.path("streamSettings");
        if (!"vless".equals(outbound.path("protocol").asString())
                || !"reality".equals(stream.path("security").asString())) {
            return Optional.empty();
        }
        JsonNode vnext = outbound.path("settings").path("vnext").path(0);
        JsonNode user = vnext.path("users").path(0);
        String network = stream.path("network").asString("tcp");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", network);
        params.put("encryption", user.path("encryption").asString("none"));
        params.put("security", "reality");
        JsonNode reality = stream.path("realitySettings");
        params.put("pbk", reality.path("publicKey").asString(""));
        params.put("fp", reality.path("fingerprint").asString(""));
        params.put("sni", reality.path("serverName").asString(""));
        params.put("sid", reality.path("shortId").asString(""));
        params.put("spx", reality.path("spiderX").asString(""));
        params.put("pqv", reality.path("mldsa65Verify").asString(""));
        params.put("flow", user.path("flow").asString(""));
        if (!transportParams(network, stream.path(network + "Settings"), params)) {
            return Optional.empty();
        }

        String query = params.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return Optional.of("vless://%s@%s:%d?%s#%s".formatted(
                user.path("id").asString(), vnext.path("address").asString(), vnext.path("port").asInt(),
                query, encode(remark)));
    }

    // false: транспорт, который в ссылку не уложить (kcp и прочая экзотика)
    private boolean transportParams(String network, JsonNode settings, Map<String, String> params) {
        switch (network) {
            case "tcp", "raw" -> {
                JsonNode header = settings.path("header");
                if ("http".equals(header.path("type").asString())) {
                    params.put("headerType", "http");
                    params.put("path", header.path("request").path("path").path(0).asString(""));
                    params.put("host", header.path("request").path("headers").path("Host").path(0).asString(""));
                }
            }
            case "ws", "httpupgrade" -> {
                params.put("path", settings.path("path").asString(""));
                params.put("host", settings.path("host").asString(""));
            }
            case "grpc" -> {
                params.put("serviceName", settings.path("serviceName").asString(""));
                params.put("authority", settings.path("authority").asString(""));
                if (settings.path("multiMode").asBoolean(false)) {
                    params.put("mode", "multi");
                }
            }
            case "xhttp" -> {
                params.put("path", settings.path("path").asString(""));
                params.put("host", settings.path("host").asString(""));
                params.put("mode", settings.path("mode").asString(""));
                // xmux и прочее, что не выражается отдельными параметрами, клиенты читают из extra
                if (settings.path("extra").isObject() && !settings.path("extra").isEmpty()) {
                    params.put("extra", jsonMapper.writeValueAsString(settings.path("extra")));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // URLEncoder кодирует пробел как "+", а в ссылках принят %20
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
