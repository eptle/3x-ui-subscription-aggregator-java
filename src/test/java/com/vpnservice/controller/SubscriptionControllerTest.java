package com.vpnservice.controller;

import com.vpnservice.service.SubscriptionService;
import com.vpnservice.service.SubscriptionService.SubscriptionContent;
import com.vpnservice.service.SubscriptionService.UserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static com.vpnservice.xray.XrayFixtures.MAPPER;
import static com.vpnservice.xray.XrayFixtures.PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionControllerTest {

    private final SubscriptionService service = mock(SubscriptionService.class);
    private final SubscriptionController controller = new SubscriptionController(service, PROPERTIES);
    private final List<ObjectNode> configs = List.of(MAPPER.createObjectNode().put("remarks", "auto"));

    @BeforeEach
    void setUp() {
        when(service.build("tok")).thenReturn(Optional.of(new SubscriptionContent(configs,
                List.of("vless://a@x:443#A", "vless://b@y:443#B"), new UserInfo(1, 2, 3, 4))));
    }

    @Test
    void jsonForHappAndUnknownClients() {
        for (String agent : List.of("Happ/3.2.1", "v2rayTun/2.4 (Android)", "INCY/1.0", "")) {
            ResponseEntity<?> response = controller.subscription("tok", agent, null);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.getBody()).isEqualTo(configs);
        }
    }

    @Test
    void base64LinksForV2rayNgAndThrone() {
        for (String agent : List.of("v2rayNG/1.10.5", "Throne/1.0.0")) {
            ResponseEntity<?> response = controller.subscription("tok", agent, null);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
            assertThat(decode(response)).isEqualTo("vless://a@x:443#A\nvless://b@y:443#B");
            assertThat(response.getHeaders().getFirst("subscription-userinfo")).isEqualTo("upload=1; download=2; total=3; expire=4");
        }
    }

    @Test
    void formatParamOverridesUserAgent() {
        assertThat(controller.subscription("tok", "Mozilla/5.0", "links").getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(controller.subscription("tok", "v2rayNG/1.10.5", "json").getBody()).isEqualTo(configs);
    }

    @Test
    void unknownToken() {
        assertThat(controller.subscription("nope", "v2rayNG", null).getStatusCode().value()).isEqualTo(404);
    }

    private static String decode(ResponseEntity<?> response) {
        return new String(Base64.getDecoder().decode((String) response.getBody()), StandardCharsets.UTF_8);
    }
}
