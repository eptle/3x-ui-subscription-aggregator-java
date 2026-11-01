package com.vpnservice.xui;

import com.vpnservice.model.Server;
import com.vpnservice.xui.dto.XuiClientEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class XuiClientTest {

    private static final String PANEL = "https://panel.example:2053/secret";
    private static final String LOGIN_OK = "{\"success\":true,\"msg\":\"ok\",\"obj\":null}";

    private final Server server = Server.builder()
            .id(1L)
            .displayName("test")
            .panelUrl(PANEL + "/")
            .panelLogin("admin")
            .panelPassword("pass")
            .publicHost("example.com")
            .build();

    private MockRestServiceServer panel;
    private XuiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        panel = MockRestServiceServer.bindTo(builder).build();
        client = new XuiClient(builder, JsonMapper.builder().build());
    }

    @Test
    void logsInAndParsesInbounds() throws Exception {
        expectLogin("3x-ui=session1");
        expectInbounds("3x-ui=session1");

        List<Inbound> inbounds = client.listInbounds(server);

        panel.verify();
        assertThat(inbounds).hasSize(1);
        Inbound inbound = inbounds.getFirst();
        assertThat(inbound.protocol()).isEqualTo("vless");
        assertThat(inbound.port()).isEqualTo(30886);
        assertThat(inbound.streamSettings().get("network").asString()).isEqualTo("tcp");

        List<XuiClientEntry> clients = inbound.clientsBySubId("zx95rirylinj93e4");
        assertThat(clients).extracting(XuiClientEntry::id).containsExactly("c29c95e2-9c76-44c3-ba3d-ad57eba215b0");
        assertThat(inbound.statFor(clients.getFirst())).isPresent();
    }

    @Test
    void reloginsWhenSessionExpired() throws Exception {
        expectLogin("3x-ui=old");
        expectInbounds("3x-ui=old");
        // так новые версии 3x-ui отвечают на запрос с протухшей сессией
        panel.expect(requestTo(PANEL + "/panel/api/inbounds/list"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        expectLogin("3x-ui=new");
        expectInbounds("3x-ui=new");

        client.listInbounds(server);
        List<Inbound> inbounds = client.listInbounds(server);

        panel.verify();
        assertThat(inbounds).hasSize(1);
    }

    @Test
    void failsOnWrongPassword() {
        panel.expect(requestTo(PANEL + "/login"))
                .andRespond(withSuccess("{\"success\":false,\"msg\":\"Invalid username or password\",\"obj\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listInbounds(server))
                .isInstanceOf(XuiException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    void addsClientAsFormWithSettingsString() {
        expectLogin("3x-ui=s");
        panel.expect(requestTo(PANEL + "/panel/api/inbounds/addClient"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, "3x-ui=s"))
                .andExpect(content().formDataContains(Map.of(
                        "id", "7",
                        "settings", "{\"clients\":[{\"id\":\"uuid\",\"email\":\"me-7\"}]}")))
                .andRespond(withSuccess("{\"success\":true,\"msg\":\"added\",\"obj\":null}", MediaType.APPLICATION_JSON));

        ObjectNode newClient = JsonMapper.builder().build().createObjectNode().put("id", "uuid").put("email", "me-7");
        client.addClient(server, 7, newClient);

        panel.verify();
    }

    @Test
    void doesNotReloginOnPanelError() {
        expectLogin("3x-ui=s");
        expectInbounds("3x-ui=s");
        panel.expect(requestTo(PANEL + "/panel/api/inbounds/addClient"))
                .andRespond(withSuccess("{\"success\":false,\"msg\":\"Something went wrong! Failed: Duplicate email: me-7\\n\",\"obj\":null}",
                        MediaType.APPLICATION_JSON));
        client.listInbounds(server);

        ObjectNode newClient = JsonMapper.builder().build().createObjectNode().put("email", "me-7");
        assertThatThrownBy(() -> client.addClient(server, 7, newClient))
                .isInstanceOf(XuiException.class)
                .hasMessage("Something went wrong! Failed: Duplicate email: me-7");
        // повторного логина не было
        panel.verify();
    }

    private void expectLogin(String cookie) {
        panel.expect(requestTo(PANEL + "/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("username", "admin", "password", "pass")))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.SET_COOKIE, cookie + "; Path=/; HttpOnly"));
    }

    private void expectInbounds(String cookie) {
        panel.expect(requestTo(PANEL + "/panel/api/inbounds/list"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.COOKIE, cookie))
                .andRespond(withSuccess(new ClassPathResource("xui/inbounds-list.json"), MediaType.APPLICATION_JSON));
    }
}
