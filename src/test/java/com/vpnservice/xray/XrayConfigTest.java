package com.vpnservice.xray;

import com.vpnservice.model.Server;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiClientEntry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static com.vpnservice.xray.XrayFixtures.CDN_STREAM;
import static com.vpnservice.xray.XrayFixtures.MAPPER;
import static com.vpnservice.xray.XrayFixtures.PROPERTIES;
import static com.vpnservice.xray.XrayFixtures.REALITY_STREAM;
import static com.vpnservice.xray.XrayFixtures.SUB_ID;
import static com.vpnservice.xray.XrayFixtures.client;
import static com.vpnservice.xray.XrayFixtures.inbound;
import static com.vpnservice.xray.XrayFixtures.server;
import static org.assertj.core.api.Assertions.assertThat;

class XrayConfigTest {

    private final OutboundFactory factory = new OutboundFactory(MAPPER, PROPERTIES);
    private final XrayConfigAssembler assembler = new XrayConfigAssembler(MAPPER);
    private final Server wurst = server(1, "🇩🇪 wurst", "wurst.whiteman.pics");

    @Test
    void soloConfigMatchesExample() throws Exception {
        Inbound reality = inbound(1, "vless", 443, REALITY_STREAM,
                client("1589ea67-9126-4e87-921e-cbd51c644091", "u-reality", SUB_ID));
        Inbound cdn = inbound(2, "vless", 8443, CDN_STREAM,
                client("f4216108-e94f-4281-8641-0389b2e689d2", "u-cdn", SUB_ID));

        TagAllocator tags = new TagAllocator();
        List<ObjectNode> outbounds = new ArrayList<>();
        outbounds.addAll(factory.build(wurst, reality, reality.settings().clients().getFirst(), tags));
        outbounds.addAll(factory.build(wurst, cdn, cdn.settings().clients().getFirst(), tags));

        ObjectNode config = assembler.solo(wurst, outbounds);

        JsonNode expected = MAPPER.readTree(new ClassPathResource("xray/solo-expected.json").getInputStream());
        assertThat(config).isEqualTo(expected);
    }

    @Test
    void forcedTlsUsesProxyAddressAsSni() {
        String stream = """
                {
                  "network": "ws",
                  "security": "none",
                  "externalProxy": [{"forceTls": "tls", "dest": "cdn.example.com", "port": 443, "remark": ""}],
                  "wsSettings": {"acceptProxyProtocol": false, "path": "/ws", "host": "cdn.example.com", "headers": {}}
                }
                """;
        Inbound inbound = inbound(1, "vless", 8080, stream, client("uuid", "u", SUB_ID));

        ObjectNode outbound = factory.build(wurst, inbound, inbound.settings().clients().getFirst(), new TagAllocator()).getFirst();

        assertThat(outbound.path("tag").asString()).isEqualTo("c-wurst");
        JsonNode vnext = outbound.path("settings").path("vnext").get(0);
        assertThat(vnext.path("address").asString()).isEqualTo("cdn.example.com");
        assertThat(vnext.path("port").asInt()).isEqualTo(443);
        JsonNode streamSettings = outbound.path("streamSettings");
        assertThat(streamSettings.path("security").asString()).isEqualTo("tls");
        assertThat(streamSettings.path("tlsSettings")).isEqualTo(MAPPER.readTree(
                "{\"serverName\": \"cdn.example.com\", \"fingerprint\": \"firefox\"}"));
        assertThat(streamSettings.path("wsSettings")).isEqualTo(MAPPER.readTree(
                "{\"path\": \"/ws\", \"host\": \"cdn.example.com\"}"));
    }

    @Test
    void tagsStayUniqueForSeveralInboundsOnOneServer() {
        Inbound first = inbound(1, "vless", 443, REALITY_STREAM, client("a", "a", SUB_ID));
        Inbound second = inbound(2, "vless", 8443, REALITY_STREAM, client("b", "b", SUB_ID));
        TagAllocator tags = new TagAllocator();

        String firstTag = factory.build(wurst, first, first.settings().clients().getFirst(), tags).getFirst().path("tag").asString();
        String secondTag = factory.build(wurst, second, second.settings().clients().getFirst(), tags).getFirst().path("tag").asString();

        assertThat(firstTag).isEqualTo("r-wurst");
        assertThat(secondTag).isEqualTo("r-wurst-2");
    }

    @Test
    void shadowsocks2022JoinsServerAndClientKeys() {
        XuiClientEntry client = new XuiClientEntry(null, "CLIENT-KEY", "ss", SUB_ID, null, null, "", true, 0, 0, 0);
        Inbound inbound = new Inbound(1, "", true, "shadowsocks", 8388,
                new InboundSettings(List.of(client), null, "2022-blake3-aes-128-gcm", "SERVER-KEY"),
                MAPPER.readTree("{\"network\": \"tcp\", \"security\": \"none\"}"), List.of());

        JsonNode server = factory.build(wurst, inbound, client, new TagAllocator()).getFirst()
                .path("settings").path("servers").get(0);

        assertThat(server.path("method").asString()).isEqualTo("2022-blake3-aes-128-gcm");
        assertThat(server.path("password").asString()).isEqualTo("SERVER-KEY:CLIENT-KEY");
    }
}
