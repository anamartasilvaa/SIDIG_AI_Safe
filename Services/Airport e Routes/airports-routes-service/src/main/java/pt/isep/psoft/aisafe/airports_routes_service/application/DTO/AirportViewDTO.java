package pt.isep.psoft.aisafe.airports_routes_service.application.DTO;

import java.util.List;
import java.time.Instant;

public record AirportViewDTO(
        String iataCode,
        String name,
        String city,
        String country,
        String timezone,
        String type,
        String status,
        List<RunwayDTO> runways,
        List<FacilityDTO> facilities,

        String imageUrl,
        String operationalHours,
        String contactInformation, Instant lastModified, String originNodeId
) {
    public AirportViewDTO(String a,String b,String c,String d,String e,String f,String g,List<RunwayDTO> h,List<FacilityDTO> i,String j,String k,String l) {
        this(a,b,c,d,e,f,g,h,i,j,k,l,null,null);
    }
}