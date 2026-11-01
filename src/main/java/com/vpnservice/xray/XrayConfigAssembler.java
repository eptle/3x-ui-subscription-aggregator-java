package com.vpnservice.xray;

import com.vpnservice.model.Server;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

// Вставляет outbound'ы в шаблоны из resources/xray. DNS, routing и балансер правятся в самих шаблонах
@Component
public class XrayConfigAssembler {

    private final ObjectNode soloTemplate;
    private final ObjectNode autoTemplate;

    public XrayConfigAssembler(JsonMapper jsonMapper) {
        this.soloTemplate = JsonResources.load(jsonMapper, "xray/solo-template.json");
        this.autoTemplate = JsonResources.load(jsonMapper, "xray/auto-template.json");
    }

    // Один сервер, балансер между его inbound'ами
    public ObjectNode solo(Server server, List<ObjectNode> outbounds) {
        ObjectNode config = assemble(soloTemplate, outbounds);
        config.put("remarks", soloTemplate.path("remarks").asString().replace("{server}", server.getDisplayName()));
        return config;
    }

    // Все серверы сразу
    public ObjectNode auto(List<ObjectNode> outbounds) {
        return assemble(autoTemplate, outbounds);
    }

    private static ObjectNode assemble(ObjectNode template, List<ObjectNode> outbounds) {
        ObjectNode config = template.deepCopy();

        // Перед direct/block из шаблона
        ArrayNode configOutbounds = (ArrayNode) config.get("outbounds");
        for (int i = 0; i < outbounds.size(); i++) {
            configOutbounds.insert(i, outbounds.get(i).deepCopy());
        }

        // Если балансеру некого выбрать, трафик идёт в fallback. CDN надёжнее, поэтому он в приоритете
        String fallbackTag = outbounds.stream()
                .map(outbound -> outbound.path("tag").asString())
                .filter(tag -> tag.startsWith("c-"))
                .findFirst()
                .orElse(outbounds.getFirst().path("tag").asString());
        for (JsonNode balancer : config.path("routing").path("balancers")) {
            ((ObjectNode) balancer).put("fallbackTag", fallbackTag);
        }
        return config;
    }
}
