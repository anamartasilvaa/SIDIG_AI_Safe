package pt.isep.psoft.aisafe.airports_routes_service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestTemplate;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.domain.Airport;
import pt.isep.psoft.aisafe.airports_routes_service.domain.AirportType;
import pt.isep.psoft.aisafe.airports_routes_service.domain.IATACode;
import pt.isep.psoft.aisafe.airports_routes_service.repositories.AirportRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A imagem do aeroporto e replicada como evento AIRPORT_IMAGE_UPDATED (so no modo AMQP). */
@ExtendWith(MockitoExtension.class)
class AirportImageReplicationTest {

    private static final String DATA_URL = "data:image/png;base64,AQID"; // bytes 1, 2, 3

    @Mock AirportRepository airportRepository;
    @Mock RestTemplate restTemplate;
    @Mock ReplicationOutbox outbox;
    @Mock AuditService auditService;

    private AirportService service;
    private Airport airport;

    @BeforeEach
    void setUp() {
        service = new AirportService(airportRepository, restTemplate, new ObjectMapper().findAndRegisterModules(),
                outbox, auditService, List.of("http://peer"), 1);
        airport = new Airport(new IATACode("OPO"), "Francisco Sa Carneiro", "Porto", "Portugal",
                "Europe/Lisbon", AirportType.INTERNATIONAL, new Coordinates(41.2481, -8.6814));
        lenient().when(airportRepository.findByIataCodeString("OPO")).thenReturn(Optional.of(airport));
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "opo.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void uploadInAmqpModeEnqueuesOneImageEvent() {
        when(airportRepository.save(any(Airport.class))).thenAnswer(i -> i.getArgument(0));
        when(outbox.isAmqp()).thenReturn(true);

        service.updateAirportImage("opo", file(), false);

        assertEquals(DATA_URL, airport.getImageUrl());
        verify(outbox).enqueue(anyList(), eq("PATCH"), eq("/api/airports/OPO/image"), eq(DATA_URL), any(), any());
    }

    @Test
    void uploadInHttpModeDoesNotEnqueueAnEvent() {
        when(airportRepository.save(any(Airport.class))).thenAnswer(i -> i.getArgument(0));
        when(outbox.isAmqp()).thenReturn(false);

        service.updateAirportImage("OPO", file(), false);

        verify(outbox, never()).enqueue(anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void imageAppliedFromAnEventIsNotRepublished() {
        when(airportRepository.save(any(Airport.class))).thenAnswer(i -> i.getArgument(0));

        service.updateAirportImage("OPO", file(), true);

        verify(outbox, never()).enqueue(anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void newerReplicatedImageIsApplied() {
        when(airportRepository.save(any(Airport.class))).thenAnswer(i -> i.getArgument(0));
        Instant newer = Instant.now().plusSeconds(3600);

        service.applyReplicatedImage("OPO", DATA_URL, newer, "airports-routes-2");

        assertEquals(DATA_URL, airport.getImageUrl());
        assertEquals(newer, airport.getLastModified());
        assertEquals("airports-routes-2", airport.getOriginNodeId());
        verify(airportRepository).save(airport);
    }

    @Test
    void olderReplicatedImageIsIgnored() {
        Instant older = Instant.now().minusSeconds(3600);

        service.applyReplicatedImage("OPO", DATA_URL, older, "airports-routes-2");

        assertNull(airport.getImageUrl());
        verify(airportRepository, never()).save(any(Airport.class));
    }

    @Test
    void replicatedImageForUnknownAirportIsNotFound() {
        when(airportRepository.findByIataCodeString("XXX")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.applyReplicatedImage("XXX", DATA_URL, Instant.now(), "airports-routes-2"));
    }
}
