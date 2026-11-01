package com.vpnservice.xui;

import com.vpnservice.model.Server;
import com.vpnservice.xui.dto.InboundSettings;
import com.vpnservice.xui.dto.XuiInbound;
import com.vpnservice.xui.dto.XuiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class XuiClient {

    private static final ParameterizedTypeReference<XuiResponse<Void>> EMPTY_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<XuiResponse<List<XuiInbound>>> INBOUNDS_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    // serverId -> значение заголовка Cookie
    private final Map<Long, String> sessions = new ConcurrentHashMap<>();

    public XuiClient(RestClient.Builder restClientBuilder, JsonMapper jsonMapper) {
        this.restClient = restClientBuilder.build();
        this.jsonMapper = jsonMapper;
    }

    public List<Inbound> listInbounds(Server server) {
        return withSession(server, cookie -> fetchInbounds(server, cookie));
    }

    public void addClient(Server server, int inboundId, ObjectNode client) {
        withSession(server, cookie -> postClient(server, cookie, inboundId, client));
    }

    // clientId: id у vless/vmess, password у trojan, email у shadowsocks
    public void deleteClient(Server server, int inboundId, String clientId) {
        withSession(server, cookie -> postDelete(server, cookie, inboundId, clientId));
    }

    private <T> T withSession(Server server, Function<String, T> call) {
        String cookie = sessions.get(server.getId());
        if (cookie != null) {
            try {
                return call.apply(cookie);
            } catch (RestClientException e) {
                // Протухшую сессию 3x-ui в зависимости от версии отдаёт как 404, 401 или редирект на логин.
                // Различать не стоит: перелогиниваемся и пробуем ещё раз.
                // XuiException (success=false, например "Duplicate email") сюда не попадает, ей перелогин не поможет
                log.debug("{}: запрос с сохранённой сессией не прошёл ({}), перелогиниваемся",
                        server.getDisplayName(), e.getMessage());
            }
        }
        cookie = login(server);
        sessions.put(server.getId(), cookie);
        return call.apply(cookie);
    }

    private String login(Server server) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", server.getPanelLogin());
        form.add("password", server.getPanelPassword());

        ResponseEntity<XuiResponse<Void>> response = restClient.post()
                .uri(url(server, "/login"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toEntity(EMPTY_RESPONSE);

        // На неверный пароль 3x-ui отвечает 200 с success=false
        XuiResponse<Void> body = response.getBody();
        if (body == null || !body.success()) {
            throw new XuiException("Логин в " + server.getPanelUrl() + " не удался: "
                    + (body == null ? "пустой ответ" : body.msg()));
        }

        List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        if (setCookies.isEmpty()) {
            throw new XuiException("Логин в " + server.getPanelUrl() + " прошёл, но cookie не пришла");
        }
        // "3x-ui=abc; Path=/; HttpOnly" -> "3x-ui=abc"
        return setCookies.stream()
                .map(header -> header.split(";", 2)[0])
                .collect(Collectors.joining("; "));
    }

    private List<Inbound> fetchInbounds(Server server, String cookie) {
        XuiResponse<List<XuiInbound>> body = restClient.get()
                .uri(url(server, "/panel/api/inbounds/list"))
                .header(HttpHeaders.COOKIE, cookie)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(INBOUNDS_RESPONSE);

        if (body == null || !body.success() || body.obj() == null) {
            throw new XuiException("Список inbound'ов с " + server.getPanelUrl() + " не получен: "
                    + (body == null ? "пустой ответ" : body.msg()));
        }
        return body.obj().stream().map(this::parse).toList();
    }

    private Void postClient(Server server, String cookie, int inboundId, ObjectNode client) {
        // settings, как и при чтении, передаётся JSON-строкой: {"clients": [...]}
        ObjectNode settings = jsonMapper.createObjectNode();
        settings.putArray("clients").add(client);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(inboundId));
        form.add("settings", jsonMapper.writeValueAsString(settings));

        XuiResponse<Void> body = restClient.post()
                .uri(url(server, "/panel/api/inbounds/addClient"))
                .header(HttpHeaders.COOKIE, cookie)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(EMPTY_RESPONSE);

        if (body == null || !body.success()) {
            throw new XuiException(body == null ? "пустой ответ" : body.msg().strip());
        }
        return null;
    }

    private Void postDelete(Server server, String cookie, int inboundId, String clientId) {
        XuiResponse<Void> body = restClient.post()
                .uri(url(server, "/panel/api/inbounds/{inboundId}/delClient/{clientId}"), inboundId, clientId)
                .header(HttpHeaders.COOKIE, cookie)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(EMPTY_RESPONSE);

        if (body == null || !body.success()) {
            throw new XuiException(body == null ? "пустой ответ" : body.msg().strip());
        }
        return null;
    }

    private Inbound parse(XuiInbound raw) {
        InboundSettings settings = jsonMapper.readValue(raw.settings(), InboundSettings.class);
        JsonNode streamSettings = raw.streamSettings() == null || raw.streamSettings().isBlank()
                ? jsonMapper.createObjectNode()
                : jsonMapper.readTree(raw.streamSettings());
        return new Inbound(raw.id(), raw.remark(), raw.enable(), raw.protocol(), raw.port(),
                settings, streamSettings, raw.clientStats());
    }

    private static String url(Server server, String path) {
        String base = server.getPanelUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }
}
