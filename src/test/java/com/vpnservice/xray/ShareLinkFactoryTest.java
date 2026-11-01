package com.vpnservice.xray;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

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

class ShareLinkFactoryTest {

    private final OutboundFactory outboundFactory = new OutboundFactory(MAPPER, PROPERTIES);
    private final ShareLinkFactory factory = new ShareLinkFactory(MAPPER);

    @Test
    void buildsRealityXhttpLink() {
        ObjectNode outbound = outbound(REALITY_STREAM);

        assertThat(factory.vlessReality(outbound, "🇩🇪 wurst")).hasValue(
                "vless://1589ea67-9126-4e87-921e-cbd51c644091@wurst.whiteman.pics:443"
                        + "?type=xhttp&encryption=none&security=reality"
                        + "&pbk=o8SFUpRQd4rP0itKveQTjHtj8uu_f-8D3aHPI-kcdS0&fp=firefox&sni=wurst.whiteman.pics&sid=8daa&spx=%2F"
                        + "&path=%2Fassets%2Fx7k2&mode=auto"
                        + "&extra=%7B%22xmux%22%3A%7B%22maxConnections%22%3A1%2C%22hMaxRequestTimes%22%3A%22600-900%22%2C%22hMaxReusableSecs%22%3A%221800-3000%22%7D%7D"
                        + "#%F0%9F%87%A9%F0%9F%87%AA%20wurst");
    }

    @Test
    void skipsCdnOutbound() {
        assertThat(factory.vlessReality(outbound(CDN_STREAM), "cdn")).isEmpty();
    }

    @Test
    void keepsFlowForTcpVision() {
        String tcpReality = REALITY_STREAM.replace("\"network\": \"xhttp\"", "\"network\": \"tcp\"");
        ObjectNode outbound = outbound(tcpReality);
        ((ObjectNode) outbound.path("settings").path("vnext").path(0).path("users").path(0)).put("flow", "xtls-rprx-vision");

        assertThat(factory.vlessReality(outbound, "x").orElseThrow())
                .contains("type=tcp&")
                .contains("&flow=xtls-rprx-vision")
                .doesNotContain("path=");
    }

    private ObjectNode outbound(String stream) {
        List<ObjectNode> outbounds = outboundFactory.build(server(1, "🇩🇪 wurst", "wurst.whiteman.pics"),
                inbound(1, "vless", 443, stream, client("1589ea67-9126-4e87-921e-cbd51c644091", "e", SUB_ID)),
                client("1589ea67-9126-4e87-921e-cbd51c644091", "e", SUB_ID), new TagAllocator());
        return outbounds.getFirst();
    }
}
