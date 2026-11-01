package com.vpnservice.service;

import com.vpnservice.model.Server;
import com.vpnservice.model.User;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.repository.UserRepository;
import com.vpnservice.snapshot.ServerSnapshot;
import com.vpnservice.snapshot.SnapshotService;
import com.vpnservice.xray.OutboundFactory;
import com.vpnservice.xray.TagAllocator;
import com.vpnservice.xray.XrayConfigAssembler;
import com.vpnservice.xui.Inbound;
import com.vpnservice.xui.dto.XuiClientEntry;
import com.vpnservice.xui.dto.XuiClientStat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final UserRepository userRepository;
    private final ServerRepository serverRepository;
    private final SnapshotService snapshotService;
    private final OutboundFactory outboundFactory;
    private final XrayConfigAssembler configAssembler;

    public Optional<SubscriptionContent> build(String token) {
        return userRepository.findByToken(token)
                .filter(User::isActive)
                .map(this::build);
    }

    private SubscriptionContent build(User user) {
        TagAllocator tags = new TagAllocator();
        List<ObjectNode> configs = new ArrayList<>();
        List<ObjectNode> allOutbounds = new ArrayList<>();
        List<XuiClientEntry> clients = new ArrayList<>();
        List<XuiClientStat> stats = new ArrayList<>();

        for (Server server : serverRepository.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            Optional<ServerSnapshot> snapshot = snapshotService.get(server.getId());
            if (snapshot.isEmpty()) {
                continue;
            }
            List<ObjectNode> outbounds = new ArrayList<>();
            for (Inbound inbound : snapshot.get().inbounds()) {
                if (!inbound.enable()) {
                    continue;
                }
                for (XuiClientEntry client : inbound.clientsBySubId(user.getSubId())) {
                    Optional<XuiClientStat> stat = inbound.statFor(client);
                    // Когда кончается трафик или срок, 3x-ui выключает клиента только в clientStats
                    if (!client.enable() || stat.map(s -> !s.enable()).orElse(false)) {
                        continue;
                    }
                    outbounds.addAll(outboundFactory.build(server, inbound, client, tags));
                    clients.add(client);
                    stat.ifPresent(stats::add);
                }
            }
            if (!outbounds.isEmpty()) {
                configs.add(configAssembler.solo(server, outbounds));
                allOutbounds.addAll(outbounds);
            }
        }

        // Общий конфиг имеет смысл, только когда серверов больше одного. Ставим его первым
        if (configs.size() > 1) {
            configs.addFirst(configAssembler.auto(allOutbounds));
        }
        return new SubscriptionContent(configs, UserInfo.of(clients, stats));
    }

    public record SubscriptionContent(List<ObjectNode> configs, UserInfo userInfo) {
    }

    // Значение заголовка subscription-userinfo: клиенты показывают трафик и дату окончания
    public record UserInfo(long upload, long download, long total, long expire) {

        static UserInfo of(List<XuiClientEntry> clients, List<XuiClientStat> stats) {
            long upload = stats.stream().mapToLong(XuiClientStat::up).sum();
            long download = stats.stream().mapToLong(XuiClientStat::down).sum();
            // Если хотя бы на одном сервере лимита нет (0), трафик считаем безлимитным
            long total = clients.stream().anyMatch(c -> c.totalGB() <= 0)
                    ? 0
                    : clients.stream().mapToLong(XuiClientEntry::totalGB).sum();
            // expiryTime в миллисекундах. 0 значит бессрочно, отрицательное значит отсчёт с первого подключения
            long expire = clients.stream().mapToLong(XuiClientEntry::expiryTime).filter(t -> t > 0).min().orElse(0) / 1000;
            return new UserInfo(upload, download, total, expire);
        }

        public String header() {
            return "upload=%d; download=%d; total=%d; expire=%d".formatted(upload, download, total, expire);
        }
    }
}
