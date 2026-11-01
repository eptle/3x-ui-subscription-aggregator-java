package com.vpnservice.service;

import com.vpnservice.model.Server;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.snapshot.SnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ServerService {

    private final ServerRepository serverRepository;
    private final SnapshotService snapshotService;

    // Создаёт сервер или обновляет существующий с тем же publicHost. Так ansible регистрирует ноды из inventory:
    // повторный прогон ничего не дублирует, а смена пароля или пути панели доезжает до агрегатора
    public UpsertResult upsert(ServerRequest request) {
        if (Stream.of(request.displayName(), request.panelUrl(), request.publicHost()).anyMatch(v -> v == null || v.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName, panelUrl и publicHost обязательны");
        }
        Server server = serverRepository.findByPublicHost(request.publicHost())
                .orElseGet(() -> Server.builder().publicHost(request.publicHost()).build());
        boolean created = server.getId() == null;
        boolean changed = created
                || !Objects.equals(server.getDisplayName(), request.displayName())
                || !Objects.equals(server.getPanelUrl(), request.panelUrl())
                || !Objects.equals(server.getPanelLogin(), request.panelLogin())
                || !Objects.equals(server.getPanelPassword(), request.panelPassword())
                || server.getSortOrder() != request.sortOrder()
                || !server.isActive();
        if (changed) {
            server.setDisplayName(request.displayName());
            server.setPanelUrl(request.panelUrl());
            server.setPanelLogin(request.panelLogin());
            server.setPanelPassword(request.panelPassword());
            server.setSortOrder(request.sortOrder());
            server.setActive(true);
            server = serverRepository.save(server);
            snapshotService.refresh(server);
        }
        return new UpsertResult(server.getId(), created, changed);
    }

    public record ServerRequest(String displayName, String panelUrl, String panelLogin, String panelPassword,
                                String publicHost, int sortOrder) {
    }

    public record UpsertResult(long id, boolean created, boolean changed) {
    }
}
