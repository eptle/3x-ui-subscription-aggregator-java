package com.vpnservice.service;

import com.vpnservice.model.Server;
import com.vpnservice.model.User;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.repository.UserRepository;
import com.vpnservice.service.UserService.CreateClientRequest;
import com.vpnservice.service.UserService.CreateClientResult;
import com.vpnservice.service.UserService.InboundResult;
import com.vpnservice.service.UserService.SyncResult;
import com.vpnservice.snapshot.SnapshotService;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.XuiClient;
import com.vpnservice.xui.XuiException;
import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiClientEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

import static com.vpnservice.xray.XrayFixtures.MAPPER;
import static com.vpnservice.xray.XrayFixtures.server;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ServerRepository serverRepository = mock(ServerRepository.class);
    private final XuiClient xuiClient = mock(XuiClient.class);
    private final SnapshotService snapshotService = mock(SnapshotService.class);
    private final UserService service = new UserService(userRepository, serverRepository, xuiClient, snapshotService, MAPPER);

    private final Server wurst = server(1, "🇩🇪 wurst", "wurst.whiteman.pics");
    private final Server dead = server(2, "🇳🇱 dead", "dead.example.com");

    @BeforeEach
    void setUp() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(wurst, dead));

        when(xuiClient.listInbounds(wurst)).thenReturn(List.of(
                inbound(1, "vless", true, ""),
                inbound(2, "vless", true, "xtls-rprx-vision"),
                inbound(3, "trojan", true, ""),
                inbound(4, "socks", true, ""),     // не поддерживается
                inbound(5, "vless", false, "")));  // выключен
        when(xuiClient.listInbounds(dead)).thenThrow(new XuiException("Логин не удался"));
    }

    @Test
    void addsClientToEveryEnabledInbound() {
        CreateClientResult result = service.createClient(new CreateClientRequest("vasya", 50, 30, 2));

        ArgumentCaptor<ObjectNode> clients = ArgumentCaptor.forClass(ObjectNode.class);
        verify(xuiClient).addClient(eq(wurst), eq(1), clients.capture());
        verify(xuiClient).addClient(eq(wurst), eq(2), clients.capture());
        verify(xuiClient).addClient(eq(wurst), eq(3), clients.capture());
        verify(xuiClient, times(3)).addClient(any(), any(Integer.class), any());

        List<ObjectNode> sent = clients.getAllValues();
        assertThat(sent).extracting(c -> c.path("email").asString()).containsExactly("vasya-1", "vasya-2", "vasya-3");
        assertThat(sent).extracting(c -> c.path("subId").asString()).containsOnly(result.subId());
        assertThat(sent).extracting(c -> c.path("totalGB").asLong()).containsOnly(50L * 1024 * 1024 * 1024);
        assertThat(sent).extracting(c -> c.path("limitIp").asInt()).containsOnly(2);
        assertThat(sent.get(0).path("flow").asString()).isEmpty();
        assertThat(sent.get(1).path("flow").asString()).isEqualTo("xtls-rprx-vision");
        assertThat(sent.get(2).path("password").asString()).hasSize(16);
        assertThat(sent.get(2).has("id")).isFalse();

        assertThat(result.subscriptionPath()).startsWith("/sub/");
        verify(snapshotService).refresh(wurst);
        verify(snapshotService, never()).refresh(dead);
    }

    @Test
    void reportsFailuresPerInboundAndServer() {
        doThrow(new XuiException("Duplicate email: vasya-2")).when(xuiClient).addClient(eq(wurst), eq(2), any());

        List<InboundResult> results = service.createClient(new CreateClientRequest("vasya", 0, 0, 0)).results();

        assertThat(results).extracting(InboundResult::inboundId, InboundResult::ok, InboundResult::error).containsExactly(
                tuple(1, true, null),
                tuple(2, false, "Duplicate email: vasya-2"),
                tuple(3, true, null),
                tuple(null, false, "Логин не удался"));
    }

    @Test
    void rejectsBadAndDuplicateNames() {
        when(userRepository.existsByName("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.createClient(new CreateClientRequest("вася пупкин", 0, 0, 0)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.createClient(new CreateClientRequest("taken", 0, 0, 0)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).save(any());
    }

    @Test
    void syncAddsMissingClientsWithCopiedLimits() {
        User vasya = User.builder().name("vasya").token("t").subId("vasya-sub").build();
        when(userRepository.findByActiveTrue()).thenReturn(List.of(vasya));
        XuiClientEntry existing = new XuiClientEntry("uuid", null, "vasya-1", "vasya-sub", "", null, null, true, 1_700_000_000_000L, 42L, 3);
        when(xuiClient.listInbounds(wurst)).thenReturn(List.of(
                inbound(1, "vless", true, List.of(existing)),
                inbound(2, "vless", true, List.of()),
                inbound(3, "trojan", true, List.of()),
                inbound(4, "vless", false, List.of())));

        SyncResult result = service.syncClients();

        ArgumentCaptor<ObjectNode> clients = ArgumentCaptor.forClass(ObjectNode.class);
        verify(xuiClient).addClient(eq(wurst), eq(2), clients.capture());
        verify(xuiClient).addClient(eq(wurst), eq(3), clients.capture());
        verify(xuiClient, times(2)).addClient(any(), any(Integer.class), any());
        assertThat(clients.getAllValues()).extracting(c -> c.path("totalGB").asLong()).containsOnly(42L);
        assertThat(clients.getAllValues()).extracting(c -> c.path("expiryTime").asLong()).containsOnly(1_700_000_000_000L);
        assertThat(clients.getAllValues()).extracting(c -> c.path("limitIp").asInt()).containsOnly(3);
        assertThat(clients.getAllValues()).extracting(c -> c.path("subId").asString()).containsOnly("vasya-sub");

        assertThat(result.added().get("vasya")).extracting(InboundResult::inboundId).containsExactly(2, 3);
        assertThat(result.unreachable()).extracting(InboundResult::server).containsExactly("🇳🇱 dead");
        verify(snapshotService).refresh(wurst);
    }

    @Test
    void deletesClientsAndKeepsUserWhenServerFailed() {
        User vasya = User.builder().id(7L).name("vasya").token("t").subId("vasya-sub").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(vasya));
        XuiClientEntry vless = new XuiClientEntry("uuid-1", null, "vasya-1", "vasya-sub", "", null, null, true, 0, 0, 0);
        XuiClientEntry trojan = new XuiClientEntry(null, "trojan-pass", "vasya-3", "vasya-sub", null, null, null, true, 0, 0, 0);
        when(xuiClient.listInbounds(wurst)).thenReturn(List.of(
                inbound(1, "vless", true, List.of(vless)),
                inbound(2, "vless", true, List.of()),
                inbound(3, "trojan", false, List.of(trojan))));

        UserService.DeleteResult result = service.deleteUser(7L);

        verify(xuiClient).deleteClient(wurst, 1, "uuid-1");
        verify(xuiClient).deleteClient(wurst, 3, "trojan-pass");
        // dead недоступен: юзер остаётся в базе, чтобы удаление можно было повторить
        assertThat(result.deleted()).isFalse();
        verify(userRepository, never()).delete(any());

        when(serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(wurst));
        assertThat(service.deleteUser(7L).deleted()).isTrue();
        verify(userRepository).delete(vasya);
    }

    private static Inbound inbound(int id, String protocol, boolean enable, String existingFlow) {
        XuiClientEntry existing = new XuiClientEntry("old", null, "old-" + id, "old-sub", existingFlow, null, null, true, 0, 0, 0);
        return inbound(id, protocol, enable, List.of(existing));
    }

    private static Inbound inbound(int id, String protocol, boolean enable, List<XuiClientEntry> clients) {
        return new Inbound(id, "inbound-" + id, enable, protocol, 443,
                new InboundSettings(clients, "none", null, null), MAPPER.createObjectNode(), List.of());
    }
}
