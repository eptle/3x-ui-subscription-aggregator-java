package com.vpnservice.snapshot;

import com.vpnservice.xui.Inbound;

import java.time.Instant;
import java.util.List;

// Последнее удачное состояние inbound'ов одного сервера
public record ServerSnapshot(long serverId, List<Inbound> inbounds, Instant fetchedAt) {
}
