package com.vpnservice.xray;

import java.util.HashSet;
import java.util.Set;

// Уникальные теги outbound'ов в пределах одной подписки: r-wurst, r-wurst-2, ...
public class TagAllocator {

    private final Set<String> used = new HashSet<>();

    public String next(String base) {
        String tag = base;
        int n = 2;
        while (!used.add(tag)) {
            tag = base + "-" + n++;
        }
        return tag;
    }
}
