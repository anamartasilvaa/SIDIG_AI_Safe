package pt.isep.psoft.aisafe.aircraft.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.core.ParameterizedTypeReference;
import pt.isep.psoft.aisafe.aircraft.application.DTO.RouteViewDTO;

import pt.isep.psoft.aisafe.aircraft.infrastructure.RemoteUnavailableException;

public class AirportsRoutesClient {

    private final RemoteCaller caller; private final java.util.List<String> urls; private final String path;
    public AirportsRoutesClient(RemoteCaller caller, java.util.List<String> urls, String path) {
        this.caller=caller; this.urls=urls; this.path=path;
    }
    public Page<RouteViewDTO> getCompatibleRoutes(Double maxRange, Integer actualCapacity, Pageable pageable) {
        RemoteUnavailableException lastFailure = null;
        for (String url: urls) try {
            String target=url.trim()+path+"?page="+pageable.getPageNumber()+"&size="+pageable.getPageSize();
            var response=caller.get(target, new ParameterizedTypeReference<java.util.List<RouteViewDTO>>() {});
            if (response.isPresent()) {
                var filtered=response.get().stream()
                        .filter(r -> r.minimumCapacity()==null || r.minimumCapacity()<=actualCapacity)
                        .filter(r -> r.minimumRange()==null || r.minimumRange()<=maxRange)
                        .toList();
                return new PageImpl<>(filtered,pageable,filtered.size());
            }
        } catch (RemoteUnavailableException failure) {
            lastFailure = failure;
        }
        throw lastFailure != null
                ? lastFailure
                : new RemoteUnavailableException("routes://unconfigured");
    }
}
