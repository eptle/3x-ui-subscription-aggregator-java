package com.vpnservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data                    // Lombok: геттеры, сеттеры, toString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    // Секрет в ссылке подписки: /sub/{token}
    @Column(unique = true, nullable = false)
    private String token;

    // subId клиента в 3x-ui, по нему клиенты юзера ищутся на всех панелях
    @Column(unique = true, nullable = false)
    private String subId;

    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
