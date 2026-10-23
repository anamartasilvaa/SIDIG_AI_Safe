package pt.isep.psoft.aisafe.flight_operations.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AggregatedResult;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.AvailabilityDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduleResultDTO;
import pt.isep.psoft.aisafe.flight_operations.application.DTO.ScheduledFlightViewDTO;
import pt.isep.psoft.aisafe.flight_operations.application.ScheduledFlightService;
import pt.isep.psoft.aisafe.flight_operations.config.ApiKeyFilter;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regras de acesso (RBAC) da API, com a cadeia do Spring Security real.
 * O ScheduledFlightService e um mock: aqui so interessa QUEM pode chamar cada endpoint.
 */
@SpringBootTest
class SecurityRulesTest {

    private static final String FLIGHT_ID = "11111111-1111-1111-1111-111111111111";
    private static final ScheduledFlightViewDTO FLIGHT = new ScheduledFlightViewDTO(FLIGHT_ID, "OPO-LIS", "CS-TTA",
            "2027-03-15T10:00", "SCHEDULED", null, null, 1, "2026-10-23T08:00:00Z");
    private static final String NEW_FLIGHT = """
            {"routeId":"OPO-LIS","registrationNumber":"CS-TTA","scheduledDateTime":"2027-03-15T10:00:00"}""";

    private static final String ATCC = TestTokens.valid("atcc.user", List.of("ATCC"));
    private static final String BACKOFFICE = TestTokens.valid("backoffice.user", List.of("BACKOFFICE"));
    private static final String ADMIN = TestTokens.valid("admin.user", List.of("ADMIN"));
    private static final String TECHNICIAN = TestTokens.valid("tech.user", List.of("MAINTENANCE_TECHNICIAN"));
    private static final String SUPERVISOR = TestTokens.valid("super.user", List.of("MAINTENANCE_SUPERVISOR"));

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilterChain;
    @Autowired ApiKeyFilter apiKeyFilter;

    @MockitoBean ScheduledFlightService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain, apiKeyFilter).build();

        when(service.scheduleFlight(any())).thenReturn(new ScheduleResultDTO(FLIGHT, List.of()));
        when(service.findById(anyString())).thenReturn(Optional.of(FLIGHT));
        when(service.changeStatus(anyString(), anyString())).thenReturn(Optional.of(FLIGHT));
        when(service.findByAircraft(anyString())).thenReturn(new AggregatedResult<>(List.of(FLIGHT), List.of()));
        when(service.routeUtilization()).thenReturn(new AggregatedResult<>(List.of(), List.of()));
        when(service.checkAvailability(anyString(), anyString())).thenReturn(new AvailabilityDTO(
                "CS-TTA", "2027-03-15T10:00", true, "ACTIVE", null, List.of(), List.of()));
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", TestTokens.bearer(token));
    }

    private static MockHttpServletRequestBuilder createFlight() {
        return post("/api/scheduled-flights").contentType(MediaType.APPLICATION_JSON).content(NEW_FLIGHT);
    }

    private static MockHttpServletRequestBuilder changeStatus() {
        return patch("/api/scheduled-flights/" + FLIGHT_ID + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELAYED\"}");
    }

    /** Passou a seguranca: nao foi 401 nem 403 (o resto depende do endpoint). */
    private static ResultMatcher allowed() {
        return result -> {
            int code = result.getResponse().getStatus();
            assertTrue(code != 401 && code != 403, "esperava acesso permitido, mas foi " + code);
        };
    }

    // ------------------------------------------------------------------ 401

    @Test
    @DisplayName("Sem token -> 401 em JSON (ProblemDetail)")
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/scheduled-flights/route-utilization"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Missing bearer token."));
    }

    @Test
    @DisplayName("Token expirado ou assinado com outro segredo -> 401")
    void invalidTokensAre401() throws Exception {
        mvc.perform(as(TestTokens.expired("atcc.user", List.of("ATCC")), createFlight()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid or expired token."));
        mvc.perform(as(TestTokens.signedWithOtherSecret("intruder", List.of("ADMIN")), createFlight()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ US212: agendar

    @Test
    @DisplayName("US212 agendar: ATCC e ADMIN podem (201)")
    void scheduleAllowed() throws Exception {
        mvc.perform(as(ATCC, createFlight())).andExpect(status().isCreated());
        mvc.perform(as(ADMIN, createFlight())).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("US212 agendar: BACKOFFICE e perfis de manutencao nao podem (403)")
    void scheduleForbidden() throws Exception {
        mvc.perform(as(BACKOFFICE, createFlight()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(as(TECHNICIAN, createFlight())).andExpect(status().isForbidden());
        mvc.perform(as(SUPERVISOR, createFlight())).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ estado do voo

    @Test
    @DisplayName("Mudar estado: ATCC pode, BACKOFFICE nao")
    void changeStatusRules() throws Exception {
        mvc.perform(as(ATCC, changeStatus())).andExpect(status().isOk());
        mvc.perform(as(BACKOFFICE, changeStatus())).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ consultas

    @Test
    @DisplayName("Consultar voo por id: ATCC, BACKOFFICE e ADMIN; manutencao nao")
    void getByIdRules() throws Exception {
        String url = "/api/scheduled-flights/" + FLIGHT_ID;
        mvc.perform(as(ATCC, get(url))).andExpect(status().isOk());
        mvc.perform(as(BACKOFFICE, get(url))).andExpect(status().isOk());
        mvc.perform(as(ADMIN, get(url))).andExpect(status().isOk());
        mvc.perform(as(TECHNICIAN, get(url))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("US213 voos de um aviao: ATCC pode, BACKOFFICE nao")
    void aircraftFlightsRules() throws Exception {
        mvc.perform(as(ATCC, get("/api/scheduled-flights/aircraft/CS-TTA"))).andExpect(status().isOk());
        mvc.perform(as(BACKOFFICE, get("/api/scheduled-flights/aircraft/CS-TTA"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("US205 disponibilidade: ATCC pode, BACKOFFICE nao")
    void availabilityRules() throws Exception {
        String url = "/api/scheduled-flights/availability?registrationNumber=CS-TTA&dateTime=2027-03-15T10:00:00";
        mvc.perform(as(ATCC, get(url))).andExpect(status().isOk());
        mvc.perform(as(BACKOFFICE, get(url))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("US229 utilizacao de rotas: BACKOFFICE e ADMIN podem, ATCC nao")
    void routeUtilizationRules() throws Exception {
        String url = "/api/scheduled-flights/route-utilization";
        mvc.perform(as(BACKOFFICE, get(url))).andExpect(allowed());
        mvc.perform(as(ADMIN, get(url))).andExpect(allowed());
        mvc.perform(as(ATCC, get(url))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Endpoint nao previsto: negado mesmo para ADMIN (deny by default)")
    void denyByDefault() throws Exception {
        mvc.perform(as(ADMIN, get("/api/qualquer-coisa"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/actuator/health e aberto (monitorizacao)")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(allowed());
    }

    @Test
    @DisplayName("/internal/** nao usa JWT: sem API key -> 401; com API key -> passa")
    void internalUsesApiKey() throws Exception {
        String url = "/internal/scheduled-flights/" + FLIGHT_ID;
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(ApiKeyFilter.HEADER, "sidis-dev-key")).andExpect(allowed());
    }
}
