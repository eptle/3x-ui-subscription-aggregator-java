package com.vpnservice.xui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Общий конверт всех ответов 3x-ui: {"success": true, "msg": "", "obj": ...}
@JsonIgnoreProperties(ignoreUnknown = true)
public record XuiResponse<T>(boolean success, String msg, T obj) {
}
