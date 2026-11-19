package pt.isep.psoft.aisafe.airports_routes_service.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** CQRS: o projetor transforma o resultado de um comando numa linha do modelo de leitura. */
@ExtendWith(MockitoExtension.class)
class AirportProjectorTest {

    @Mock AirportReadRepository repository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private AirportViewDTO view() {
        return new AirportViewDTO("OPO", "Francisco Sa Carneiro", "Porto", "Portugal", "Europe/Lisbon",
                "INTERNATIONAL", "OPERATIONAL", List.of(), List.of(), null, "24h", "info@opo.pt",
                Instant.parse("2026-11-15T10:00:00Z"), "airports-routes-1");
    }

    @Test
    void projectSavesTheSearchableColumnsAndTheFullViewJson() {
        new AirportProjector(repository, mapper).project(view());

        ArgumentCaptor<AirportReadModel> captor = ArgumentCaptor.forClass(AirportReadModel.class);
        verify(repository).save(captor.capture());
        AirportReadModel row = captor.getValue();
        assertEquals("OPO", row.getIataCode());
        assertEquals("Porto", row.getCity());
        assertEquals("Portugal", row.getCountry());
        assertEquals("OPERATIONAL", row.getStatus());
        assertEquals(Instant.parse("2026-11-15T10:00:00Z"), row.getLastModified());
        assertEquals("airports-routes-1", row.getOriginNodeId());
        assertTrue(row.getViewJson().contains("\"iataCode\":\"OPO\""));
    }

    @Test
    void projectingTheSameAirportTwiceOverwritesTheRowByKey() {
        AirportProjector projector = new AirportProjector(repository, mapper);

        projector.project(view());
        projector.project(view());

        ArgumentCaptor<AirportReadModel> captor = ArgumentCaptor.forClass(AirportReadModel.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals(captor.getAllValues().get(0).getIataCode(), captor.getAllValues().get(1).getIataCode());
    }

    @Test
    void serializationFailureIsReportedAsIllegalState() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") { });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new AirportProjector(repository, broken).project(view()));

        assertTrue(ex.getMessage().contains("OPO"));
        verifyNoInteractions(repository);
    }
}
