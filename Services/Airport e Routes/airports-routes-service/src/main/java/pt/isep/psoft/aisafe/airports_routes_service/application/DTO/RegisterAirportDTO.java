package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.util.List;
import java.time.Instant;

public record RegisterAirportDTO(
        String iataCode,
        String name,
        String city,
        String country,
        String timezone,
        String type,
        Double latitude,
        Double longitude,
        List<RunwayDTO> runways,
        List<FacilityDTO> facilities,

        String imageUrl,
        Instant lastModified,
        String originNodeId
) {
    public RegisterAirportDTO(String a,String b,String c,String d,String e,String f,Double g,Double h,List<RunwayDTO> i,List<FacilityDTO> j,String k) {
        this(a,b,c,d,e,f,g,h,i,j,k,null,null);
    }
}