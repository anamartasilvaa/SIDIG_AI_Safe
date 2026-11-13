package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;

/**
 * CQRS, PROJETOR dos aeroportos: transforma o estado resultante de um comando (ou de um evento
 * replicado recebido do broker) na vista guardada no modelo de leitura.
 *
 * E chamado na mesma transacao do comando, por isso o modelo de leitura desta instancia
 * nunca fica atras do modelo de escrita; as outras instancias atualizam-se ao aplicar o evento.
 */
@Component
public class AirportProjector {

    private final AirportReadRepository repository;
    private final ObjectMapper mapper;

    public AirportProjector(AirportReadRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void project(AirportViewDTO view) {
        try {
            repository.save(new AirportReadModel(
                    view.iataCode(), view.name(), view.city(), view.country(), view.status(),
                    view.lastModified(), view.originNodeId(), mapper.writeValueAsString(view)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Nao foi possivel projetar o aeroporto " + view.iataCode(), exception);
        }
    }
}
