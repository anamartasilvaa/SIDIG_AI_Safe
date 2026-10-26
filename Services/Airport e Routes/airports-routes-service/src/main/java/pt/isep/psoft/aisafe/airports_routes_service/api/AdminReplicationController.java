package pt.isep.psoft.aisafe.airports_routes_service.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.ReplicationTaskViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.ReplicationTaskRepository;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminReplicationController {
    private final ReplicationTaskRepository repository;

    public AdminReplicationController(ReplicationTaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/replication-tasks")
    public List<ReplicationTaskViewDTO> getReplicationTasks() {
        return repository.findAll().stream()
                .map(ReplicationTaskViewDTO::from)
                .toList();
    }
}
