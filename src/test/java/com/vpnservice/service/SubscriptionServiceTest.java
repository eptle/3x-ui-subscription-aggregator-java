package com.vpnservice.service;

import com.vpnservice.model.Server;
import com.vpnservice.model.User;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.repository.UserRepository;
import com.vpnservice.service.SubscriptionService.SubscriptionContent;
import com.vpnservice.snapshot.ServerSnapshot;
import com.vpnservice.snapshot.SnapshotService;
import com.vpnservice.xray.OutboundFactory;
import com.vpnservice.xray.XrayConfigAssembler;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiClientEntry;
import com.vpnservice.xui.dto.XuiClientStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.vpnservice.xray.XrayFixtures.CDN_STREAM;
import static com.vpnservice.xray.XrayFixtures.MAPPER;
import static com.vpnservice.xray.XrayFixtures.PROPERTIES;
import static com.vpnservice.xray.XrayFixtures.REALITY_STREAM;
import static com.vpnservice.xray.XrayFixtures.SUB_ID;
import static com.vpnservice.xray.XrayFixtures.client;
import static com.vpnservice.xray.XrayFixtures.inbound;
import static com.vpnservice.xray.XrayFixtures.server;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ServerRepository serverRepository = mock(ServerRepository.class);
    private final SnapshotService snapshotService = mock(SnapshotService.class);
    private final SubscriptionService service = new SubscriptionService(userRepository, serverRepository, snapshotService,
            new OutboundFactory(MAPPER, PROPERTIES), new XrayConfigAssembler(MAPPER));

    private final Server wurst = server(1, "🇩🇪 wurst", "wurst.whiteman.pics");
    private final Server node2 = server(2, "🇳🇱 node2", "node2.example.com");
    private final Server offline = server(3, "🇫🇮 offline", "offline.example.com");

    @BeforeEach
    void setUp() {
        User user = User.builder().id(1L).name("test").token("secret").subId(SUB_ID).build();
        when(userRepository.findByToken("secret")).thenReturn(Optional.of(user));
        when(serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(wurst, node2, offline));

        // На CDN-inbound'е есть клиент чужого юзера, он в подписку попасть не должен
        Inbound wurstReality = inbound(1, "vless", 443, REALITY_STREAM, client("w-r", "w-r", SUB_ID));
        Inbound wurstCdn = inbound(2, "vless", 8443, CDN_STREAM, client("w-c", "w-c", SUB_ID), client("x", "x", "other-user"));
        snapshot(wurst, wurstReality, wurstCdn);

        // На node2 второй клиент юзера выключен панелью (кончился трафик)
        XuiClientEntry depleted = client("n-depleted", "n-depleted", SUB_ID);
        Inbound node2Depleted = new Inbound(2, "", true, "vless", 8443,
                new InboundSettings(List.of(depleted), "none", null, null), MAPPER.readTree(REALITY_STREAM),
                List.of(new XuiClientStat("n-depleted", false, 999, 999, 0, 0)));
        snapshot(node2, inbound(1, "vless", 443, REALITY_STREAM, client("n-r", "n-r", SUB_ID)), node2Depleted);

        // offline ещё ни разу не ответил, снимка нет
        when(snapshotService.get(3L)).thenReturn(Optional.empty());
    }

    @Test
    void buildsAutoConfigFirstAndOneConfigPerServer() {
        SubscriptionContent content = service.build("secret").orElseThrow();

        assertThat(content.configs()).extracting(c -> c.path("remarks").asString())
                .containsExactly("⚡ Авто (все ноды)", "🇩🇪 wurst (авто)", "🇳🇱 node2 (авто)");

        JsonNode auto = content.configs().getFirst();
        assertThat(tags(auto)).containsExactly("r-wurst", "c-wurst", "r-node2", "direct", "block");
        assertThat(auto.path("routing").path("balancers").get(0).path("fallbackTag").asString()).isEqualTo("c-wurst");

        JsonNode node2Config = content.configs().get(2);
        assertThat(tags(node2Config)).containsExactly("r-node2", "direct", "block");
        assertThat(node2Config.path("routing").path("balancers").get(0).path("fallbackTag").asString()).isEqualTo("r-node2");
    }

    @Test
    void sumsTrafficOfUsedClientsOnly() {
        SubscriptionContent content = service.build("secret").orElseThrow();

        // три клиента по up=100/down=200, выключенный не считается
        assertThat(content.userInfo().header()).isEqualTo("upload=300; download=600; total=0; expire=0");
    }

    @Test
    void unknownTokenGivesNothing() {
        when(userRepository.findByToken("wrong")).thenReturn(Optional.empty());

        assertThat(service.build("wrong")).isEmpty();
    }

    private void snapshot(Server server, Inbound... inbounds) {
        when(snapshotService.get(server.getId()))
                .thenReturn(Optional.of(new ServerSnapshot(server.getId(), List.of(inbounds), Instant.now())));
    }

    private static List<String> tags(JsonNode config) {
        return config.path("outbounds").valueStream().map(o -> o.path("tag").asString()).toList();
    }
}
