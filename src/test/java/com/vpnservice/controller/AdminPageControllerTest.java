package com.vpnservice.controller;

import com.vpnservice.model.Server;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.repository.UserRepository;
import com.vpnservice.service.UserService.CreateClientResult;
import com.vpnservice.service.UserService.DeleteResult;
import com.vpnservice.service.UserService.InboundResult;
import com.vpnservice.service.UserService.SyncResult;
import com.vpnservice.xui.XuiClient;
import com.vpnservice.xui.XuiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:admintestdb?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        // не create: в Hibernate 7.2 он создаёт в SQLite колонку id без типа, и id остаются NULL
        "spring.jpa.hibernate.ddl-auto=update",
        "aggregator.refresh-interval=1h",
        "spring.security.user.name=admin",
        "spring.security.user.password=test-pass"
})
@AutoConfigureMockMvc
@WithMockUser
class AdminPageControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ServerRepository serverRepository;
    @MockitoBean
    private XuiClient xuiClient;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        serverRepository.deleteAll();
        when(xuiClient.listInbounds(any())).thenThrow(new XuiException("Логин не удался"));
    }

    @Test
    @WithAnonymousUser
    void redirectsAnonymousToLogin() throws Exception {
        // Браузер (Accept: text/html) отправляется на форму логина, curl получает 401 для Basic
        mvc.perform(get("/admin/users").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/sub/whatever")).andExpect(status().isNotFound());
        // /error открыт, иначе Security запоминает его и после логина отправляет на /error?continue
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML)).andExpect(status().is5xxServerError());
    }

    @Test
    @WithAnonymousUser
    void loginWithoutSavedPageGoesToAdmin() throws Exception {
        // На проде корень домена отдаёт nginx, поэтому после входа нельзя уходить на /
        mvc.perform(formLogin().user("admin").password("test-pass")).andExpect(redirectedUrl("/admin/users"));
        mvc.perform(formLogin().user("admin").password("wrong")).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void createsUserAndShowsLink() throws Exception {
        mvc.perform(post("/admin/users").with(csrf())
                        .param("name", "vasya").param("totalGb", "10").param("expiryDays", "30").param("limitIp", "2"))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("created", "createdUrl"));

        String token = userRepository.findAll().getFirst().getToken();
        mvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("vasya")))
                .andExpect(content().string(containsString("http://localhost/sub/" + token)));
    }

    @Test
    void rejectsBadName() throws Exception {
        mvc.perform(post("/admin/users").with(csrf())
                        .param("name", "вася").param("totalGb", "0").param("expiryDays", "0").param("limitIp", "0"))
                .andExpect(flash().attributeExists("error"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void savesServerAndShowsPollError() throws Exception {
        mvc.perform(post("/admin/servers").with(csrf())
                        .param("displayName", "🇩🇪 wurst").param("sortOrder", "1")
                        .param("panelUrl", "https://wurst.example.com/path").param("panelLogin", "admin")
                        .param("panelPassword", "pa55w0rd").param("publicHost", "wurst.example.com")
                        .param("active", "true").param("_active", "on"))
                .andExpect(redirectedUrl("/admin/servers"));

        Server saved = serverRepository.findAll().getFirst();
        assertThat(saved.getPanelPassword()).isEqualTo("pa55w0rd");
        mvc.perform(get("/admin/servers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Логин не удался")))
                .andExpect(content().string(not(containsString("pa55w0rd"))));

        // Пустой пароль при редактировании не затирает старый, снятый флажок выключает сервер
        mvc.perform(post("/admin/servers").with(csrf())
                        .param("id", saved.getId().toString())
                        .param("displayName", "🇩🇪 wurst").param("sortOrder", "1")
                        .param("panelUrl", "https://wurst.example.com/path").param("panelLogin", "admin")
                        .param("panelPassword", "").param("publicHost", "wurst.example.com")
                        .param("_active", "on"))
                .andExpect(redirectedUrl("/admin/servers"));
        Server edited = serverRepository.findById(saved.getId()).orElseThrow();
        assertThat(edited.getPanelPassword()).isEqualTo("pa55w0rd");
        assertThat(edited.isActive()).isFalse();

        mvc.perform(get("/admin/servers").param("edit", saved.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Изменить: 🇩🇪 wurst")));
    }

    @Test
    void syncAndDeleteRender() throws Exception {
        mvc.perform(post("/admin/sync").with(csrf()))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("syncResult"));
        mvc.perform(get("/admin/users").flashAttr("syncResult",
                        new SyncResult(List.of(), Map.of())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Всё уже на месте")));

        List<InboundResult> results = List.of(
                new InboundResult("🇩🇪 wurst", 1, "reality", true, null),
                new InboundResult("🇳🇱 dead", null, null, false, "Логин не удался"));
        mvc.perform(get("/admin/users")
                        .flashAttr("created", new CreateClientResult("vasya", "sub", "/sub/tok", results))
                        .flashAttr("createdUrl", "http://localhost/sub/tok")
                        .flashAttr("deleteResult", new DeleteResult("petya", false, results)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Создан vasya")))
                .andExpect(content().string(containsString("1 reality")))
                .andExpect(content().string(containsString("petya остался в базе")));
    }
}
