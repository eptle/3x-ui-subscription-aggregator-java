package com.vpnservice.repository;

import com.vpnservice.model.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    // SELECT * FROM servers WHERE active = true
    List<Server> findByActiveTrue();

    // Порядок серверов в подписке
    List<Server> findByActiveTrueOrderBySortOrderAscIdAsc();
}