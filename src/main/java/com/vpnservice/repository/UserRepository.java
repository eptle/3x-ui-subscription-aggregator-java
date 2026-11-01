package com.vpnservice.repository;

import com.vpnservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Spring сам напишет SELECT * FROM users WHERE token = ?
    Optional<User> findByToken(String token);

    boolean existsByName(String name);

    List<User> findByActiveTrue();
}