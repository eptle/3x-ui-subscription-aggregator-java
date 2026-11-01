package com.vpnservice.xui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// Inbound в том виде, в каком его отдаёт /panel/api/inbounds/list
@JsonIgnoreProperties(ignoreUnknown = true)
public record XuiInbound(
        int id,
        String remark,
        boolean enable,
        String protocol,
        int port,
        String settings,        // JSON внутри строки, см. InboundSettings
        String streamSettings,  // JSON внутри строки
        List<XuiClientStat> clientStats) {
}
