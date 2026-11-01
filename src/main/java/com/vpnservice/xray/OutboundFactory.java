package com.vpnservice.xray;

import com.vpnservice.config.AggregatorProperties;
import com.vpnservice.model.Server;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiClientEntry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Inbound сервера + клиент -> outbound'ы для клиентского конфига Xray
@Component
public class OutboundFactory {

    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("vless", "vmess", "trojan", "shadowsocks");

    private final JsonMapper jsonMapper;
    private final ObjectNode xhttpExtra;
    private final String defaultFingerprint;

    public OutboundFactory(JsonMapper jsonMapper, AggregatorProperties properties) {
        this.jsonMapper = jsonMapper;
        this.xhttpExtra = JsonResources.load(jsonMapper, "xray/xhttp-extra.json");
        this.defaultFingerprint = properties.defaultFingerprint();
    }

    // По outbound'у на каждую точку входа: адрес сервера либо каждый externalProxy (например, CDN-домен)
    public List<ObjectNode> build(Server server, Inbound inbound, XuiClientEntry client, TagAllocator tags) {
        if (!SUPPORTED_PROTOCOLS.contains(inbound.protocol())) {
            return List.of();
        }
        JsonNode proxies = inbound.streamSettings().path("externalProxy");
        if (!proxies.isArray() || proxies.isEmpty()) {
            return List.of(outbound(server, inbound, client, server.getPublicHost(), inbound.port(), "same", tags));
        }
        List<ObjectNode> outbounds = new ArrayList<>();
        for (JsonNode proxy : proxies) {
            outbounds.add(outbound(server, inbound, client, text(proxy, "dest", server.getPublicHost()),
                    proxy.path("port").asInt(inbound.port()), text(proxy, "forceTls", "same"), tags));
        }
        return outbounds;
    }

    private ObjectNode outbound(Server server, Inbound inbound, XuiClientEntry client,
                                String address, int port, String forceTls, TagAllocator tags) {
        ObjectNode stream = streamSettings(inbound.streamSettings(), forceTls, address);
        String prefix = "reality".equals(stream.path("security").asString()) ? "r-" : "c-";

        ObjectNode outbound = jsonMapper.createObjectNode();
        outbound.put("tag", tags.next(prefix + slug(server)));
        outbound.put("protocol", inbound.protocol());
        outbound.set("settings", protocolSettings(inbound, client, address, port));
        outbound.set("streamSettings", stream);
        return outbound;
    }

    private ObjectNode protocolSettings(Inbound inbound, XuiClientEntry client, String address, int port) {
        ObjectNode settings = jsonMapper.createObjectNode();
        switch (inbound.protocol()) {
            case "vless" -> {
                ObjectNode user = vnextUser(settings, address, port);
                user.put("id", client.id());
                user.put("encryption", orDefault(inbound.settings().encryption(), "none"));
                user.put("flow", orDefault(client.flow(), ""));
            }
            case "vmess" -> {
                ObjectNode user = vnextUser(settings, address, port);
                user.put("id", client.id());
                user.put("security", orDefault(client.security(), "auto"));
            }
            case "trojan" -> server(settings, address, port).put("password", client.password());
            case "shadowsocks" -> {
                String method = orDefault(client.method(), inbound.settings().method());
                ObjectNode server = server(settings, address, port);
                server.put("method", method);
                server.put("password", shadowsocksPassword(inbound.settings(), client, method));
            }
            default -> throw new IllegalArgumentException("Протокол не поддерживается: " + inbound.protocol());
        }
        return settings;
    }

    private ObjectNode vnextUser(ObjectNode settings, String address, int port) {
        ObjectNode vnext = settings.putArray("vnext").addObject();
        vnext.put("address", address);
        vnext.put("port", port);
        return vnext.putArray("users").addObject();
    }

    private ObjectNode server(ObjectNode settings, String address, int port) {
        ObjectNode server = settings.putArray("servers").addObject();
        server.put("address", address);
        server.put("port", port);
        return server;
    }

    // В SS-2022 с несколькими пользователями клиент шлёт "ключ_сервера:ключ_клиента"
    private static String shadowsocksPassword(InboundSettings settings, XuiClientEntry client, String method) {
        if (method != null && method.startsWith("2022-blake3-aes") && settings.password() != null) {
            return settings.password() + ":" + client.password();
        }
        return orDefault(client.password(), settings.password());
    }

    private ObjectNode streamSettings(JsonNode source, String forceTls, String address) {
        String network = text(source, "network", "tcp");
        String security = text(source, "security", "none");
        // externalProxy может навязать TLS (CDN перед сервером без TLS) или убрать его
        if ("tls".equals(forceTls) || "none".equals(forceTls)) {
            security = forceTls;
        }

        ObjectNode stream = jsonMapper.createObjectNode();
        stream.put("network", network);
        stream.put("security", security);
        switch (security) {
            case "reality" -> stream.set("realitySettings", realitySettings(source.path("realitySettings")));
            case "tls" -> stream.set("tlsSettings", tlsSettings(source.path("tlsSettings"), address));
            default -> {
            }
        }

        // Настройки транспорта у сервера и клиента общие, поэтому копируем как есть
        String transportKey = network + "Settings";
        if (source.path(transportKey) instanceof ObjectNode transport) {
            ObjectNode copy = transport.deepCopy();
            copy.remove("acceptProxyProtocol");
            removeEmpty(copy);
            if ("xhttp".equals(network)) {
                ObjectNode extra = copy.path("extra") instanceof ObjectNode existing ? existing : copy.putObject("extra");
                extra.setAll(xhttpExtra.deepCopy());
            }
            stream.set(transportKey, copy);
        }
        return stream;
    }

    // У inbound'а здесь приватный ключ. Клиенту нужна публичная часть из settings и по одному serverName/shortId
    private ObjectNode realitySettings(JsonNode source) {
        JsonNode client = source.path("settings");
        ObjectNode reality = jsonMapper.createObjectNode();
        reality.put("serverName", first(source.path("serverNames"), text(client, "serverName", "")));
        reality.put("fingerprint", text(client, "fingerprint", defaultFingerprint));
        reality.put("publicKey", text(client, "publicKey", ""));
        reality.put("shortId", first(source.path("shortIds"), ""));
        reality.put("spiderX", text(client, "spiderX", "/"));
        String mldsa65Verify = text(client, "mldsa65Verify", null);
        if (mldsa65Verify != null) {
            reality.put("mldsa65Verify", mldsa65Verify);
        }
        return reality;
    }

    // Пустой source, если TLS навязан через externalProxy: тогда SNI берётся из адреса
    private ObjectNode tlsSettings(JsonNode source, String address) {
        ObjectNode tls = jsonMapper.createObjectNode();
        tls.put("serverName", text(source, "serverName", address));
        tls.put("fingerprint", text(source.path("settings"), "fingerprint", defaultFingerprint));
        JsonNode alpn = source.path("alpn");
        if (alpn.isArray() && !alpn.isEmpty()) {
            tls.set("alpn", alpn.deepCopy());
        }
        return tls;
    }

    // Панель хранит "host": "", "headers": {} и т.п. В клиентском конфиге они только шумят
    private static void removeEmpty(ObjectNode node) {
        List<String> empty = node.properties().stream()
                .filter(e -> e.getValue().isString() ? e.getValue().asString().isEmpty() : e.getValue().isContainer() && e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        empty.forEach(node::remove);
    }

    // wurst.whiteman.pics -> wurst
    private static String slug(Server server) {
        return server.getPublicHost().split("\\.")[0];
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : fallback;
    }

    private static String first(JsonNode array, String fallback) {
        return array.isArray() && !array.isEmpty() && !array.get(0).asString().isBlank()
                ? array.get(0).asString()
                : fallback;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
