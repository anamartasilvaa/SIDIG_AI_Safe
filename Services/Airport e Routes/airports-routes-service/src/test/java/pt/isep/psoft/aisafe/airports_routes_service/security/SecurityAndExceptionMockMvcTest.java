package pt.isep.psoft.aisafe.airports_routes_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportQueryService;
import pt.isep.psoft.aisafe.airports_routes_service.application.AirportService;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteQueryService;
import pt.isep.psoft.aisafe.airports_routes_service.application.RouteService;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.AirportViewDTO;
import pt.isep.psoft.aisafe.airports_routes_service.api.exception.NotFoundException;
import pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RegisterAirportDTO;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"peer.urls=", "app.replication.transport=http",
        "spring.datasource.url=jdbc:h2:mem:mockmvc-test;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SecurityAndExceptionMockMvcTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AirportService airportService;
    @MockitoBean AirportQueryService airportQueryService;
    @MockitoBean RouteService routeService;
    @MockitoBean RouteQueryService routeQueryService;

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/airports/LIS"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongProfile() throws Exception {
        mockMvc.perform(post("/api/airports")
                        .with(user("atcc").roles("ATCC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iataCode\":\"LIS\",\"name\":\"Lisbon\",\"city\":\"Lisbon\",\"country\":\"PT\","
                                + "\"timezone\":\"Europe/Lisbon\",\"type\":\"INTERNATIONAL\",\"latitude\":1,\"longitude\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ignoresSyncHeaderWithoutPeerRole() throws Exception {
        AirportViewDTO dto = new AirportViewDTO("LIS", "Lisbon", "Lisbon", "PT", "Europe/Lisbon",
                "INTERNATIONAL", "OPERATIONAL", List.of(), List.of(), null, null, null);

        when(airportService.registerAirport(any(RegisterAirportDTO.class), eq(false), any(), any()))
                .thenReturn(dto);

        mockMvc.perform(post("/api/airports")
                        .with(user("backoffice").roles("BACKOFFICE"))
                        .header("X-P2P-Sync", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iataCode\":\"LIS\",\"name\":\"Lisbon\",\"city\":\"Lisbon\",\"country\":\"PT\","
                                + "\"timezone\":\"Europe/Lisbon\",\"type\":\"INTERNATIONAL\",\"latitude\":1,\"longitude\":2}"))
                .andExpect(status().isCreated());

        verify(airportService).registerAirport(any(RegisterAirportDTO.class), eq(false), any(), any());
    }

    @Test
    void mapsDuplicateTo409() throws Exception {
        // Ajustado para aceitar os 4 parâmetros também aqui
        when(airportService.registerAirport(any(RegisterAirportDTO.class), eq(false), any(), any()))
                .thenThrow(new IllegalStateException("Airport already exists"));

        mockMvc.perform(post("/api/airports")
                        .with(user("backoffice").roles("BACKOFFICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iataCode\":\"LIS\",\"name\":\"Lisbon\",\"city\":\"Lisbon\",\"country\":\"PT\","
                                + "\"timezone\":\"Europe/Lisbon\",\"type\":\"INTERNATIONAL\",\"latitude\":1,\"longitude\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptsPeerReplicationHeader() throws Exception {
        AirportViewDTO dto = new AirportViewDTO("LIS", "Lisbon", "Lisbon", "PT",
                "Europe/Lisbon", "INTERNATIONAL", "OPERATIONAL", List.of(), List.of(), null, null, null);
        when(airportService.registerAirport(any(RegisterAirportDTO.class), eq(true), any(), any())).thenReturn(dto);

        mockMvc.perform(post("/api/airports")
                        .with(user("peer").roles("PEER"))
                        .header("X-P2P-Sync", "true")
                        .header("X-Last-Modified", "2026-01-01T00:00:00Z")
                        .header("X-Origin-Node", "peer-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iataCode\":\"LIS\",\"name\":\"Lisbon\",\"city\":\"Lisbon\",\"country\":\"PT\","
                                + "\"timezone\":\"Europe/Lisbon\",\"type\":\"INTERNATIONAL\",\"latitude\":1,\"longitude\":2}"))
                .andExpect(status().isCreated());

        verify(airportService).registerAirport(any(RegisterAirportDTO.class), eq(true), any(), eq("peer-a"));
    }

    @Test
    void mapsNotFoundTo404() throws Exception {
        when(airportQueryService.getAirportByIataCode(anyString(), anyInt()))
                .thenThrow(new NotFoundException("missing"));

        mockMvc.perform(get("/api/airports/LIS").with(user("atcc").roles("ATCC")))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsBadRequestTo400() throws Exception {
        when(airportService.updateAirportStatus(anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("invalid"));

        mockMvc.perform(patch("/api/airports/LIS/status")
                        .with(user("backoffice").roles("BACKOFFICE"))
                        .contentType(MediaType.APPLICATION_JSON).content("\"invalid\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsConflictTo409() throws Exception {
        when(airportService.updateAirportStatus(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenThrow(new IllegalStateException("conflict"));

        mockMvc.perform(patch("/api/airports/LIS/status")
                        .with(user("backoffice").roles("BACKOFFICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("CLOSED")) // Envia apenas a string pura que o @RequestBody String espera
                .andExpect(status().isConflict());
    }
    @Test
    void mapsPeerFailureTo503() throws Exception {
        when(airportQueryService.getAirportByIataCode(anyString(), anyInt()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("offline"));

        mockMvc.perform(get("/api/airports/LIS").with(user("atcc").roles("ATCC")))
                .andExpect(status().isServiceUnavailable());
    }


    @Test
    void airportHateoasSelfLinkContainsIata() throws Exception {
        AirportViewDTO dto = new AirportViewDTO("LIS", "Lisbon", "Lisbon", "PT",
                "Europe/Lisbon", "INTERNATIONAL", "OPERATIONAL", List.of(), List.of(), null, null, null);
        when(airportQueryService.getAirportByIataCode(eq("LIS"), anyInt())).thenReturn(dto);

        mockMvc.perform(get("/api/airports/LIS").with(user("atcc").roles("ATCC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self.href").value(org.hamcrest.Matchers.containsString("/api/airports/LIS")));
    }

    @Test
    void airportSearchHateoasSelfLinkContainsSearch() throws Exception {
        when(airportQueryService.searchAirports(any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/airports/search?name=Lisbon").with(user("atcc").roles("ATCC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self.href").value(org.hamcrest.Matchers.containsString("/api/airports/search")));
    }

    @Test
    void routeHateoasSelfLinkContainsId() throws Exception {
        when(routeQueryService.getRoute(eq("RT-1"), anyInt()))
                .thenReturn(new pt.isep.psoft.aisafe.airports_routes_service.application.DTO.RouteViewDTO(
                        "RT-1", "LIS", "OPO", "ACTIVE", 100));

        mockMvc.perform(get("/api/routes/RT-1").with(user("atcc").roles("ATCC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self.href").value(org.hamcrest.Matchers.containsString("/api/routes/RT-1")));
    }
}
