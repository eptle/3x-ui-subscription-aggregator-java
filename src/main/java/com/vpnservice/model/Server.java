package com.vpnservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "servers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Название для remarks в подписке, например "🇩🇪 wurst"
    @Column(nullable = false)
    private String displayName;

    // Адрес панели вместе со схемой и webBasePath: https://1.2.3.4:2053/randompath
    @Column(nullable = false)
    private String panelUrl;

    private String panelLogin;
    private String panelPassword;

    // Адрес, который получают клиенты (домен), если у inbound нет externalProxy
    @Column(nullable = false)
    private String publicHost;

    private int sortOrder;

    @Builder.Default
    private boolean active = true;
}
