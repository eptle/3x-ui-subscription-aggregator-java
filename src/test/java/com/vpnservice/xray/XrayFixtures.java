package com.vpnservice.xray;

import com.vpnservice.config.AggregatorProperties;
import com.vpnservice.model.Server;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiClientEntry;
import com.vpnservice.xui.dto.XuiClientStat;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;

// Inbound'ы в том виде, в каком их хранит 3x-ui, под конфиги из solo-config-example.json
public final class XrayFixtures {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    public static final AggregatorProperties PROPERTIES = new AggregatorProperties("VPN", 12, "firefox", null);
    public static final String SUB_ID = "wurst-user";

    public static final String REALITY_STREAM = """
            {
              "network": "xhttp",
              "security": "reality",
              "externalProxy": [],
              "realitySettings": {
                "show": false,
                "xver": 0,
                "target": "127.0.0.1:8443",
                "serverNames": ["wurst.whiteman.pics"],
                "privateKey": "SERVER-PRIVATE-KEY",
                "shortIds": ["8daa", "c1d2e3f4"],
                "mldsa65Seed": "",
                "settings": {
                  "publicKey": "o8SFUpRQd4rP0itKveQTjHtj8uu_f-8D3aHPI-kcdS0",
                  "fingerprint": "firefox",
                  "serverName": "",
                  "spiderX": "/",
                  "mldsa65Verify": ""
                }
              },
              "xhttpSettings": {"path": "/assets/x7k2", "host": "", "headers": {}, "mode": "auto"},
              "sockopt": {"acceptProxyProtocol": false, "tcpFastOpen": true}
            }
            """;

    public static final String CDN_STREAM = """
            {
              "network": "xhttp",
              "security": "tls",
              "externalProxy": [{"forceTls": "same", "dest": "cdn-wurst.whiteman.pics", "port": 443, "remark": ""}],
              "tlsSettings": {
                "serverName": "cdn-wurst.whiteman.pics",
                "minVersion": "1.2",
                "certificates": [{"certificateFile": "/root/cert.pem", "keyFile": "/root/key.pem"}],
                "alpn": ["h2"],
                "settings": {"allowInsecure": false, "fingerprint": "firefox"}
              },
              "xhttpSettings": {"path": "/static/k3p9", "host": "cdn-wurst.whiteman.pics", "headers": {}, "mode": "packet-up"}
            }
            """;

    private XrayFixtures() {
    }

    public static Server server(long id, String displayName, String publicHost) {
        return Server.builder().id(id).displayName(displayName).publicHost(publicHost).panelUrl("https://" + publicHost).build();
    }

    public static XuiClientEntry client(String id, String email, String subId) {
        return new XuiClientEntry(id, null, email, subId, "", null, null, true, 0, 0, 0);
    }

    public static Inbound inbound(int id, String protocol, int port, String stream, XuiClientEntry... clients) {
        List<XuiClientStat> stats = Arrays.stream(clients)
                .map(c -> new XuiClientStat(c.email(), true, 100, 200, 0, 0))
                .toList();
        return new Inbound(id, "", true, protocol, port,
                new InboundSettings(List.of(clients), "none", null, null),
                MAPPER.readTree(stream), stats);
    }
}
