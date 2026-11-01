package com.vpnservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Подписку клиенты забирают без логина, секретом служит токен в URL
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/sub/**").permitAll()
                        // Иначе запрос к /error (например, браузер не нашёл /favicon.ico) запоминается
                        // как страница "куда вернуться", и после логина открывается /error?continue
                        .requestMatchers("/error", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                // REST-ручки зовутся curl'ом с Basic-авторизацией, без браузерной сессии CSRF-токен им не нужен
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults())
                .build();
    }
}
