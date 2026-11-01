package com.vpnservice.snapshot;

import com.vpnservice.model.Server;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.XuiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final ServerRepository serverRepository;
    private final XuiClient xuiClient;

    // serverId -> снимок
    private final Map<Long, ServerSnapshot> snapshots = new ConcurrentHashMap<>();
    // serverId -> ошибка последнего опроса, если он не удался. Для статуса в админке
    private final Map<Long, RefreshError> errors = new ConcurrentHashMap<>();

    // Первый запуск сразу после старта приложения, дальше через refresh-interval после окончания предыдущего
    @Scheduled(fixedDelayString = "${aggregator.refresh-interval:5m}")
    public void refreshAll() {
        List<Server> servers = serverRepository.findByActiveTrue();

        // Медленный сервер не задерживает остальные. close() ждёт, пока закончат все
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            servers.forEach(server -> executor.submit(() -> refresh(server)));
        }

        // Снимки удалённых и выключенных серверов больше не нужны
        Set<Long> activeIds = servers.stream().map(Server::getId).collect(Collectors.toSet());
        snapshots.keySet().retainAll(activeIds);
        errors.keySet().retainAll(activeIds);
    }

    public void refresh(Server server) {
        try {
            List<Inbound> inbounds = xuiClient.listInbounds(server);
            snapshots.put(server.getId(), new ServerSnapshot(server.getId(), inbounds, Instant.now()));
            errors.remove(server.getId());
            log.info("{}: получено inbound'ов: {}", server.getDisplayName(), inbounds.size());
        } catch (Exception e) {
            // Не бросаем дальше: в кэше остаётся прошлый снимок, подписка продолжает его отдавать
            errors.put(server.getId(), new RefreshError(describe(e), Instant.now()));
            log.warn("{}: обновить не удалось, в кэше {}: {}", server.getDisplayName(),
                    get(server.getId()).map(snapshot -> "снимок от " + snapshot.fetchedAt()).orElse("снимка нет"),
                    e.toString());
        }
    }

    public Optional<RefreshError> lastError(long serverId) {
        return Optional.ofNullable(errors.get(serverId));
    }

    // У HTTP-ошибок в сообщении целая HTML-страница nginx, для статуса хватит кода
    private static String describe(Exception e) {
        return e instanceof RestClientResponseException http
                ? "HTTP " + http.getStatusCode().value() + " " + http.getStatusText()
                : e.getMessage();
    }

    public record RefreshError(String message, Instant at) {
    }

    public Optional<ServerSnapshot> get(long serverId) {
        return Optional.ofNullable(snapshots.get(serverId));
    }
}
