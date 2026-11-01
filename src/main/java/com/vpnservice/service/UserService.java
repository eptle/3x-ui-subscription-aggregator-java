package com.vpnservice.service;

import com.vpnservice.model.Server;
import com.vpnservice.model.User;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.repository.UserRepository;
import com.vpnservice.snapshot.SnapshotService;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.XuiClient;
import com.vpnservice.xui.dto.XuiClientEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";
    // Имя идёт в email клиента на панелях
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("vless", "vmess", "trojan");

    private final UserRepository userRepository;
    private final ServerRepository serverRepository;
    private final XuiClient xuiClient;
    private final SnapshotService snapshotService;
    private final JsonMapper jsonMapper;

    // Заводит юзера и добавляет ему клиента во все включённые inbound'ы всех активных серверов.
    // Ошибка на одном сервере остальные не останавливает: всё видно в results
    public CreateClientResult createClient(CreateClientRequest request) {
        if (request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name: латиница, цифры, _ и -, до 32 символов");
        }
        if (request.limitIp() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limitIp: 0 (без ограничений) или больше");
        }
        if (userRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Юзер " + request.name() + " уже есть");
        }

        User user = userRepository.save(User.builder()
                .name(request.name())
                .token(randomToken())
                .subId(randomString(16))
                .build());

        Limits limits = new Limits(
                request.totalGb() > 0 ? request.totalGb() * 1024L * 1024 * 1024 : 0,
                request.expiryDays() > 0 ? Instant.now().plus(Duration.ofDays(request.expiryDays())).toEpochMilli() : 0,
                request.limitIp());

        List<InboundResult> results = new ArrayList<>();
        for (Server server : serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            try {
                for (Inbound inbound : xuiClient.listInbounds(server)) {
                    if (inbound.enable() && SUPPORTED_PROTOCOLS.contains(inbound.protocol())) {
                        results.add(addToInbound(server, inbound, user, limits));
                    }
                }
                // Чтобы подписка заработала сразу, а не после следующего опроса
                snapshotService.refresh(server);
            } catch (RuntimeException e) {
                results.add(new InboundResult(server.getDisplayName(), null, null, false, e.getMessage()));
            }
        }
        return new CreateClientResult(user.getName(), user.getSubId(), "/sub/" + user.getToken(), results);
    }

    // Досоздаёт активным юзерам клиентов во всех включённых inbound'ах, где их ещё нет:
    // нужно после добавления VPS или нового inbound'а. Лимиты копируются с клиента юзера на другом сервере
    public SyncResult syncClients() {
        List<InboundResult> unreachable = new ArrayList<>();
        Map<Server, List<Inbound>> inbounds = new LinkedHashMap<>();
        for (Server server : serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            try {
                inbounds.put(server, xuiClient.listInbounds(server).stream()
                        .filter(inbound -> inbound.enable() && SUPPORTED_PROTOCOLS.contains(inbound.protocol()))
                        .toList());
            } catch (RuntimeException e) {
                unreachable.add(new InboundResult(server.getDisplayName(), null, null, false, e.getMessage()));
            }
        }

        Map<String, List<InboundResult>> added = new LinkedHashMap<>();
        Set<Server> changed = new HashSet<>();
        for (User user : userRepository.findByActiveTrue()) {
            Optional<XuiClientEntry> template = inbounds.values().stream()
                    .flatMap(List::stream)
                    .flatMap(inbound -> inbound.clientsBySubId(user.getSubId()).stream())
                    .findFirst();
            Limits limits = template
                    .map(client -> new Limits(client.totalGB(), client.expiryTime(), client.limitIp()))
                    .orElse(Limits.NONE);

            inbounds.forEach((server, serverInbounds) -> {
                for (Inbound inbound : serverInbounds) {
                    if (inbound.clientsBySubId(user.getSubId()).isEmpty()) {
                        added.computeIfAbsent(user.getName(), name -> new ArrayList<>())
                                .add(addToInbound(server, inbound, user, limits));
                        changed.add(server);
                    }
                }
            });
        }

        changed.forEach(snapshotService::refresh);
        return new SyncResult(unreachable, added);
    }

    // Удаляет клиентов юзера со всех активных серверов. Из базы юзер уходит, только если ошибок не было:
    // иначе его клиенты остались бы на панели рабочими, а повторить удаление было бы уже нечем
    public DeleteResult deleteUser(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Юзер не найден"));

        List<InboundResult> results = new ArrayList<>();
        for (Server server : serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            try {
                boolean changed = false;
                for (Inbound inbound : xuiClient.listInbounds(server)) {
                    for (XuiClientEntry client : inbound.clientsBySubId(user.getSubId())) {
                        results.add(deleteFromInbound(server, inbound, client));
                        changed = true;
                    }
                }
                if (changed) {
                    snapshotService.refresh(server);
                }
            } catch (RuntimeException e) {
                results.add(new InboundResult(server.getDisplayName(), null, null, false, e.getMessage()));
            }
        }

        boolean deleted = results.stream().allMatch(InboundResult::ok);
        if (deleted) {
            userRepository.delete(user);
        }
        return new DeleteResult(user.getName(), deleted, results);
    }

    private InboundResult deleteFromInbound(Server server, Inbound inbound, XuiClientEntry client) {
        String clientId = switch (inbound.protocol()) {
            case "trojan" -> client.password();
            case "shadowsocks" -> client.email();
            default -> client.id();
        };
        try {
            xuiClient.deleteClient(server, inbound.id(), clientId);
            return new InboundResult(server.getDisplayName(), inbound.id(), inbound.remark(), true, null);
        } catch (RuntimeException e) {
            log.warn("{}: клиента {} из inbound {} удалить не удалось: {}",
                    server.getDisplayName(), client.email(), inbound.id(), e.getMessage());
            return new InboundResult(server.getDisplayName(), inbound.id(), inbound.remark(), false, e.getMessage());
        }
    }

    private InboundResult addToInbound(Server server, Inbound inbound, User user, Limits limits) {
        ObjectNode client = jsonMapper.createObjectNode();
        switch (inbound.protocol()) {
            case "vless" -> {
                client.put("id", UUID.randomUUID().toString());
                client.put("flow", flowOf(inbound));
            }
            case "vmess" -> {
                client.put("id", UUID.randomUUID().toString());
                client.put("security", "auto");
            }
            case "trojan" -> client.put("password", randomString(16));
            default -> throw new IllegalArgumentException("Протокол не поддерживается: " + inbound.protocol());
        }
        // email должен быть уникален в пределах панели, а у юзера по клиенту в каждом inbound'е
        client.put("email", user.getName() + "-" + inbound.id());
        client.put("limitIp", limits.limitIp());
        client.put("totalGB", limits.totalBytes());
        client.put("expiryTime", limits.expiryTime());
        client.put("enable", true);
        client.put("subId", user.getSubId());
        client.put("comment", "");
        client.put("reset", 0);

        try {
            xuiClient.addClient(server, inbound.id(), client);
            return new InboundResult(server.getDisplayName(), inbound.id(), inbound.remark(), true, null);
        } catch (RuntimeException e) {
            log.warn("{}: клиента в inbound {} добавить не удалось: {}", server.getDisplayName(), inbound.id(), e.getMessage());
            return new InboundResult(server.getDisplayName(), inbound.id(), inbound.remark(), false, e.getMessage());
        }
    }

    // flow берём у уже существующих клиентов inbound'а: на tcp+reality это обычно xtls-rprx-vision, на xhttp пусто
    private static String flowOf(Inbound inbound) {
        return inbound.settings().clients().stream()
                .map(XuiClientEntry::flow)
                .filter(flow -> flow != null && !flow.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    // totalGb, expiryDays и limitIp (сколько IP одновременно): 0 значит без ограничений
    public record CreateClientRequest(String name, int totalGb, int expiryDays, int limitIp) {
    }

    // Лимиты клиента в единицах 3x-ui: байты, epoch millis, число IP
    private record Limits(long totalBytes, long expiryTime, int limitIp) {
        static final Limits NONE = new Limits(0, 0, 0);
    }

    public record CreateClientResult(String name, String subId, String subscriptionPath, List<InboundResult> results) {
    }

    // inboundId == null: сервер не ответил целиком (логин, сеть)
    public record InboundResult(String server, Integer inboundId, String remark, boolean ok, String error) {
    }

    public record DeleteResult(String name, boolean deleted, List<InboundResult> results) {
    }

    // unreachable: серверы, с которых не удалось получить inbound'ы; added: имя юзера → что ему досоздано
    public record SyncResult(List<InboundResult> unreachable, Map<String, List<InboundResult>> added) {
    }
}
