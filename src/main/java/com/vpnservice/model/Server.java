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

    private String name;        // например "VPS Frankfurt"
    private String subPort;
    private String xuiIp;     // https://your-vps.com
    private String xuiPort;
    private String xuiLogin;
    private String xuiPassword;
    private boolean active = true;
}