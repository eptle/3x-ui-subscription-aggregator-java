package com.vpnservice.xray;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

final class JsonResources {

    private JsonResources() {
    }

    static ObjectNode load(JsonMapper jsonMapper, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return (ObjectNode) jsonMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + path, e);
        }
    }
}
