package pt.isep.psoft.aisafe.airports_routes_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// AI-assisted (GitHub Copilot): enables the transactional replication outbox worker.
public class AirportsRoutesServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AirportsRoutesServiceApplication.class, args);
	}

}
