package com.vpnservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:sqlite:file:testdb?mode=memory&cache=shared",
		"spring.datasource.hikari.maximum-pool-size=1",
		"spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
		"spring.jpa.hibernate.ddl-auto=update"
})
class VpnServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
