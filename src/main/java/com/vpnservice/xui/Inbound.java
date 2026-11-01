package com.vpnservice.xui;

import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiClientEntry;
import com.vpnservice.xui.dto.XuiClientStat;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

// Inbound с уже разобранными settings и streamSettings
public record Inbound(
        int id,
        String remark,
        boolean enable,
        String protocol,
        int port,
        InboundSettings settings,
        JsonNode streamSettings,
        List<XuiClientStat> clientStats) {

    public Inbound {
        clientStats = clientStats == null ? List.of() : clientStats;
    }

    public List<XuiClientEntry> clientsBySubId(String subId) {
        return settings.clients().stream()
                .filter(client -> subId.equals(client.subId()))
                .toList();
    }

    public Optional<XuiClientStat> statFor(XuiClientEntry client) {
        return clientStats.stream()
                .filter(stat -> stat.email().equals(client.email()))
                .findFirst();
    }
}
