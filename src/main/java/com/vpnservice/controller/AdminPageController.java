package com.vpnservice.controller;

import com.vpnservice.config.AggregatorProperties;
import com.vpnservice.model.Server;
import com.vpnservice.model.User;
import com.vpnservice.repository.ServerRepository;
import com.vpnservice.repository.UserRepository;
import com.vpnservice.service.SubscriptionService;
import com.vpnservice.service.SubscriptionService.SubscriptionContent;
import com.vpnservice.service.SubscriptionService.UserInfo;
import com.vpnservice.service.UserService;
import com.vpnservice.service.UserService.CreateClientRequest;
import com.vpnservice.snapshot.ServerSnapshot;
import com.vpnservice.snapshot.SnapshotService;
import com.vpnservice.snapshot.SnapshotService.RefreshError;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

// Веб-админка. Действия по схеме POST -> redirect -> GET, результат передаётся через flash-атрибуты
@Controller
@RequiredArgsConstructor
public class AdminPageController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault());

    private final UserRepository userRepository;
    private final ServerRepository serverRepository;
    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final SnapshotService snapshotService;
    private final AggregatorProperties properties;

    // Сюда formLogin отправляет после входа
    @GetMapping({"/", "/admin"})
    public String index() {
        return "redirect:/admin/users";
    }

    // ---------- Пользователи ----------

    @GetMapping("/admin/users")
    public String users(Model model) {
        String baseUrl = baseUrl();
        List<UserRow> rows = userRepository.findAll(Sort.by("name")).stream()
                .map(user -> UserRow.of(user, baseUrl, subscriptionService.build(user.getToken())))
                .toList();
        model.addAttribute("users", rows);
        return "admin/users";
    }

    @PostMapping("/admin/users")
    public String createUser(@ModelAttribute CreateClientRequest request, RedirectAttributes redirect) {
        try {
            var result = userService.createClient(request);
            redirect.addFlashAttribute("created", result);
            redirect.addFlashAttribute("createdUrl", baseUrl() + result.subscriptionPath());
        } catch (ResponseStatusException e) {
            redirect.addFlashAttribute("error", e.getReason());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/delete")
    public String deleteUser(@PathVariable long id, RedirectAttributes redirect) {
        redirect.addFlashAttribute("deleteResult", userService.deleteUser(id));
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/sync")
    public String sync(RedirectAttributes redirect) {
        redirect.addFlashAttribute("syncResult", userService.syncClients());
        return "redirect:/admin/users";
    }

    // ---------- Серверы ----------

    @GetMapping("/admin/servers")
    public String servers(@RequestParam Optional<Long> edit, Model model) {
        List<ServerRow> rows = serverRepository.findAll(Sort.by("sortOrder", "id")).stream()
                .map(server -> ServerRow.of(server, snapshotService.get(server.getId()), snapshotService.lastError(server.getId())))
                .toList();
        Server form = edit.flatMap(serverRepository::findById).orElseGet(() -> Server.builder().build());
        // Пароль в форму не отдаём: пустое поле при сохранении значит "не менять"
        form.setPanelPassword(null);
        model.addAttribute("servers", rows);
        model.addAttribute("form", form);
        return "admin/servers";
    }

    @PostMapping("/admin/servers")
    public String saveServer(@ModelAttribute Server form, RedirectAttributes redirect) {
        if (form.getId() != null && (form.getPanelPassword() == null || form.getPanelPassword().isEmpty())) {
            serverRepository.findById(form.getId()).ifPresent(old -> form.setPanelPassword(old.getPanelPassword()));
        }
        Server saved = serverRepository.save(form);
        // Сразу проверяем доступ к панели, чтобы статус в таблице был актуальным
        if (saved.isActive()) {
            snapshotService.refresh(saved);
        }
        redirect.addFlashAttribute("message", "Сервер " + saved.getDisplayName() + " сохранён");
        return "redirect:/admin/servers";
    }

    @PostMapping("/admin/servers/{id}/delete")
    public String deleteServer(@PathVariable long id, RedirectAttributes redirect) {
        serverRepository.deleteById(id);
        redirect.addFlashAttribute("message", "Сервер удалён из агрегатора. Клиенты на его панели остались как были");
        return "redirect:/admin/servers";
    }

    private String baseUrl() {
        String url = properties.publicUrl() != null && !properties.publicUrl().isBlank()
                ? properties.publicUrl()
                : ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return "%.1f КБ".formatted(bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return "%.1f МБ".formatted(bytes / (1024.0 * 1024));
        }
        return "%.2f ГБ".formatted(bytes / (1024.0 * 1024 * 1024));
    }

    // servers: на скольких серверах у юзера есть рабочие конфиги (общий "Авто" не считается)
    public record UserRow(long id, String name, String created, String url, int servers, String traffic, String expire) {

        static UserRow of(User user, String baseUrl, Optional<SubscriptionContent> content) {
            int configs = content.map(c -> c.configs().size()).orElse(0);
            UserInfo info = content.map(SubscriptionContent::userInfo).orElse(new UserInfo(0, 0, 0, 0));
            String traffic = formatBytes(info.upload() + info.download())
                    + " / " + (info.total() > 0 ? formatBytes(info.total()) : "∞");
            String expire = info.expire() > 0 ? DATE.format(Instant.ofEpochSecond(info.expire())) : "бессрочно";
            String created = user.getCreatedAt() == null ? "" : user.getCreatedAt().toLocalDate().toString();
            return new UserRow(user.getId(), user.getName(), created, baseUrl + "/sub/" + user.getToken(),
                    configs > 1 ? configs - 1 : configs, traffic, expire);
        }
    }

    // error: null, если последний опрос удался
    public record ServerRow(Server server, int inbounds, String fetchedAt, String error) {

        static ServerRow of(Server server, Optional<ServerSnapshot> snapshot, Optional<RefreshError> error) {
            return new ServerRow(server,
                    snapshot.map(s -> s.inbounds().size()).orElse(0),
                    snapshot.map(s -> DATE_TIME.format(s.fetchedAt())).orElse(null),
                    error.map(e -> DATE_TIME.format(e.at()) + ": " + e.message()).orElse(null));
        }
    }
}
