package com.vpnservice.service;

import com.vpnservice.model.Server;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.service.ServerService.ServerRequest;
import com.vpnservice.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerServiceTest {

    private final ServerRepository repository = mock(ServerRepository.class);
    private final SnapshotService snapshotService = mock(SnapshotService.class);
    private final ServerService service = new ServerService(repository, snapshotService);

    private final ServerRequest request = new ServerRequest("🇩🇪 wurst", "https://wurst.example.com/path",
            "admin", "pass", "wurst.example.com", 1);

    @Test
    void createsNewServer() {
        when(repository.findByPublicHost("wurst.example.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            Server server = invocation.getArgument(0);
            server.setId(5L);
            return server;
        });

        assertThat(service.upsert(request)).isEqualTo(new ServerService.UpsertResult(5, true, true));
        verify(snapshotService).refresh(any());
    }

    @Test
    void sameDataChangesNothing() {
        Server existing = Server.builder().id(5L).displayName("🇩🇪 wurst").panelUrl("https://wurst.example.com/path")
                .panelLogin("admin").panelPassword("pass").publicHost("wurst.example.com").sortOrder(1).build();
        when(repository.findByPublicHost("wurst.example.com")).thenReturn(Optional.of(existing));

        assertThat(service.upsert(request).changed()).isFalse();
        verify(repository, never()).save(any());

        existing.setPanelPassword("old");
        existing.setActive(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.upsert(request)).isEqualTo(new ServerService.UpsertResult(5, false, true));
        assertThat(existing.getPanelPassword()).isEqualTo("pass");
        assertThat(existing.isActive()).isTrue();
    }

    @Test
    void requiresHostAndUrl() {
        assertThatThrownBy(() -> service.upsert(new ServerRequest("x", "", null, null, "h", 0)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
