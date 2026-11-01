package com.vpnservice.controller;

import com.vpnservice.service.UserService;
import com.vpnservice.service.UserService.CreateClientRequest;
import com.vpnservice.service.UserService.CreateClientResult;
import com.vpnservice.service.UserService.SyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    @PostMapping("/create-client")
    public CreateClientResult createClient(@RequestBody CreateClientRequest request) {
        return userService.createClient(request);
    }

    @PostMapping("/sync-clients")
    public SyncResult syncClients() {
        return userService.syncClients();
    }

    // Без этого Boot прячет текст ошибки, и клиент видит только код
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(e.getBody());
    }
}
