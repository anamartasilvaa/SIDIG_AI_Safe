package pt.isep.psoft.aisafe.aircraft.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;


import pt.isep.psoft.aisafe.aircraft.application.DTO.*;
import pt.isep.psoft.aisafe.aircraft.domain.*;
import pt.isep.psoft.aisafe.aircraft.repositories.AircraftModelRepository;
import pt.isep.psoft.aisafe.aircraft.repositories.AircraftRepository;
import pt.isep.psoft.aisafe.aircraft.infrastructure.FlightsServiceClient;
import pt.isep.psoft.aisafe.aircraft.infrastructure.AirportsRoutesClient;
import pt.isep.psoft.aisafe.aircraft.infrastructure.AircraftPeerClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AircraftService {

    private final AircraftModelRepository modelRepository;
    private final AircraftRepository aircraftRepository;

    private final FlightsServiceClient flightsClient;
    private final AirportsRoutesClient airportsClient;
    private final AircraftPeerClient peerClient;
    private final pt.isep.psoft.aisafe.aircraft.infrastructure.AircraftReplicator replicator;
    private pt.isep.psoft.aisafe.aircraft.messaging.AircraftEventPublisher events;

    public AircraftService(AircraftModelRepository modelRepository,
                           AircraftRepository aircraftRepository,
                           FlightsServiceClient flightsClient,
                           AirportsRoutesClient airportsClient,
                           AircraftPeerClient peerClient,
                           pt.isep.psoft.aisafe.aircraft.infrastructure.AircraftReplicator replicator) {
        this.modelRepository = modelRepository;
        this.aircraftRepository = aircraftRepository;
        this.flightsClient = flightsClient;
        this.airportsClient = airportsClient;
        this.peerClient = peerClient;
        this.replicator = replicator;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(pt.isep.psoft.aisafe.aircraft.messaging.AircraftEventPublisher events) {
        this.events = events;
    }

    @Transactional
    public void incrementAircraftAssignments(String regNum) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum.trim().toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + regNum));

        aircraft.incrementAssignments();
        aircraftRepository.save(aircraft);
        replicator.replicate(mapToViewDTO(aircraft));
    }

    @Transactional
    public void applyMaintenanceStatus(String registrationNumber, String status) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(
                new RegistrationNumber(registrationNumber.trim().toUpperCase())).orElse(null);
        if (aircraft == null) return;
        AircraftStatus target = "UNDER_MAINTENANCE".equalsIgnoreCase(status)
                ? AircraftStatus.UNDER_MAINTENANCE : AircraftStatus.ACTIVE;
        if (aircraft.getStatus() != target) {
            aircraft.updateStatus(target);
            aircraftRepository.save(aircraft);
        }
    }

    @Transactional
    public void applyFlightAssignment(String registrationNumber) {
        aircraftRepository.findByRegistrationNumber(
                new RegistrationNumber(registrationNumber.trim().toUpperCase())).ifPresent(aircraft -> {
            aircraft.incrementAssignments();
            aircraftRepository.save(aircraft);
        });
    }

    @Transactional
    public void applyFlightCompletion(String registrationNumber, double flightHours) {
        aircraftRepository.findByRegistrationNumber(
                new RegistrationNumber(registrationNumber.trim().toUpperCase())).ifPresent(aircraft -> {
            aircraft.addFlightHours(flightHours);
            aircraftRepository.save(aircraft);
        });
    }

    private String saveImageLocally(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        try {
            Path uploadPath = Paths.get("uploads/");
            if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Files.copy(file.getInputStream(), uploadPath.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/" + fileName;
        } catch (IOException e) {
            throw new RuntimeException("Error saving image " + e.getMessage());
        }
    }

    @Transactional
    public void registerModel(RegisterAircraftModelDTO dto) {
        AircraftModel model = new AircraftModel(
                new ModelName(dto.modelName()), Manufacturer.valueOf(dto.manufacturer().toUpperCase()),
                dto.seatingCapacity(), dto.fuelCapacity(), dto.range(), dto.speed(),
                dto.fuelConsumption(),
                dto.modelPhotoUrl(), dto.seatingConfiguration(), dto.operatingHoursRange(), dto.engineType()
        );
        modelRepository.save(model);
    }

    @Transactional
    public void registerAircraft(RegisterAircraftDTO dto) {
        AircraftModel model = modelRepository.findByModelName(new ModelName(dto.modelName()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft model not found: " + dto.modelName()));
        Aircraft aircraft = new Aircraft(
                new RegistrationNumber(dto.registrationNumber()), model,
                dto.manufacturingDate(), dto.seatingCapacity(), dto.features()
        );
        Aircraft saved = aircraftRepository.save(aircraft);
        replicator.replicate(mapToViewDTO(saved));   // push the new aircraft to the peer replicas
        if (events != null) events.aircraftChanged(saved);
    }

    public AircraftViewDTO getAircraftByRegistrationNumber(String regNum, boolean isPeerRequest) {
        // 1. Check local DB
        Optional<Aircraft> localAircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum));
        if (localAircraft.isPresent()) {
            return mapToViewDTO(localAircraft.get());
        }

        // 2. If not found locally, and we aren't already forwarding, use the peer client
        if (!isPeerRequest) {
            Optional<AircraftViewDTO> remoteAircraft = peerClient.forwardGetAircraftByRegNum(regNum);
            if (remoteAircraft.isPresent()) {
                return remoteAircraft.get();
            }
        }

        throw new IllegalArgumentException("Aircraft not found: " + regNum);
    }

    // Kept for backward compatibility with older controller calls if needed
    public AircraftViewDTO getAircraftByRegistrationNumber(String regNum) {
        return getAircraftByRegistrationNumber(regNum, false);
    }
    public AggregatedResult<AircraftViewDTO> searchAircrafts(String model, String status, Integer year) {
        List<Aircraft> results;
        if (year != null) results = aircraftRepository.findByManufacturingYear(year);
        else if (status != null && !status.isBlank()) results = aircraftRepository.findByStatus(AircraftStatus.valueOf(status.toUpperCase()));
        else if (model != null && !model.isBlank()) results = aircraftRepository.findByModel_ModelName(new ModelName(model));
        else results = aircraftRepository.findAll();
        List<AircraftViewDTO> localResults = results.stream().map(this::mapToViewDTO).collect(Collectors.toList());

        Map<String, AircraftViewDTO> merged = new LinkedHashMap<>();
        localResults.forEach(a -> merged.put(a.registrationNumber(), a));
        AggregatedResult<AircraftViewDTO> remote = peerClient.forwardSearchAircrafts(model, status, year);
        remote.items().forEach(a -> merged.putIfAbsent(a.registrationNumber(), a));
        return new AggregatedResult<>(new ArrayList<>(merged.values()), remote.unreachablePeers());
    }

    public Optional<AircraftViewDTO> findLocalAircraft(String regNum) {
        return aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum.trim().toUpperCase())).map(this::mapToViewDTO);
    }

    @Transactional
    public void applyReplica(AircraftViewDTO dto) {
        Optional<Aircraft> existing = aircraftRepository.findByRegistrationNumber(
                new RegistrationNumber(dto.registrationNumber()));
        if (existing.isPresent()) {
            Aircraft current = existing.get();
            if (current.getRevision() >= dto.revision()) return;
            current.updateStatus(AircraftStatus.valueOf(dto.status().replace(' ', '_').toUpperCase()));
            aircraftRepository.save(current);
            return;
        }
        AircraftModel model = modelRepository.findByModelName(new ModelName(dto.modelName()))
                .orElse(null);
        if (model == null) return; // model replication is independent and may arrive first
        Aircraft created = new Aircraft(new RegistrationNumber(dto.registrationNumber()), model,
                dto.manufacturingDate(), dto.seatingCapacity(), dto.features());
        aircraftRepository.save(created);
    }
    public List<AircraftViewDTO> searchLocalAircrafts(String model, String status, Integer year) {
        // Internal endpoints must never forward.
        List<Aircraft> results;
        if (year != null) results=aircraftRepository.findByManufacturingYear(year);
        else if (status != null && !status.isBlank()) results=aircraftRepository.findByStatus(AircraftStatus.valueOf(status.toUpperCase()));
        else if (model != null && !model.isBlank()) results=aircraftRepository.findByModel_ModelName(new ModelName(model));
        else results=aircraftRepository.findAll();
        return results.stream().map(this::mapToViewDTO).toList();
    }

    @Transactional
    public Map<String, Object> updateAircraftStatusWithReport(String regNum, UpdateAircraftStatusDTO dto) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + regNum));

        AircraftStatus newStatus = AircraftStatus.valueOf(dto.status().toUpperCase());
        aircraft.updateStatus(newStatus);
        aircraftRepository.save(aircraft);
        replicator.replicate(mapToViewDTO(aircraft));   // push the new status to the peer replicas
        if (events != null) events.aircraftChanged(aircraft);

        List<String> swapLogs = new ArrayList<>();
        if (newStatus == AircraftStatus.UNDER_MAINTENANCE || newStatus == AircraftStatus.INACTIVE) {
            // Find local active candidates of the same model
            List<String> candidateRegNums = aircraftRepository.findAll().stream()
                    .filter(a -> a.getAircraftModel().equals(aircraft.getAircraftModel())
                            && a.getStatus() == AircraftStatus.ACTIVE
                            && !a.equals(aircraft))
                    .map(a -> a.getRegistrationNumber().number())
                    .collect(Collectors.toList());

            // Delegate the actual flight reassignment logic to the Flights Service Client
            swapLogs = flightsClient.handleAircraftGrounding(regNum, candidateRegNums);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("aircraft", mapToViewDTO(aircraft));
        response.put("swapReport", swapLogs);
        return response;
    }


    @Transactional
    public AircraftModelViewDTO updateModelSpecifications(String modelName, UpdateAircraftModelSpecsDTO dto) {
        AircraftModel model = modelRepository.findByModelName(new ModelName(modelName.trim().toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft model not found: " + modelName));
        model.updateSpecifications(dto.seatingCapacity(), dto.fuelCapacity(), dto.maximumRange(), dto.cruisingSpeed(), dto.seatingConfiguration(), dto.operatingHoursRange());
        AircraftModel savedModel = modelRepository.save(model);
        return new AircraftModelViewDTO(savedModel.getModelName().name(), savedModel.getManufacturer().name(), savedModel.getDefaultSeatingCapacity(), savedModel.getFuelCapacity(), savedModel.getMaximumRange(), savedModel.getCruisingSpeed(), savedModel.getModelPhotoUrl(), savedModel.getSeatingConfiguration(), savedModel.getOperatingHoursRange(), savedModel.getEngineType());
    }

    @Transactional
    public AircraftModelViewDTO updateModelImage(String modelName, MultipartFile file) {
        AircraftModel model = modelRepository.findByModelName(new ModelName(modelName.trim().toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft model not found: " + modelName));
        model.updateImage(saveImageLocally(file));
        AircraftModel savedModel = modelRepository.save(model);
        return new AircraftModelViewDTO(savedModel.getModelName().name(), savedModel.getManufacturer().name(), savedModel.getDefaultSeatingCapacity(), savedModel.getFuelCapacity(), savedModel.getMaximumRange(), savedModel.getCruisingSpeed(), savedModel.getModelPhotoUrl(), savedModel.getSeatingConfiguration(), savedModel.getOperatingHoursRange(), savedModel.getEngineType());
    }

    private AircraftViewDTO mapToViewDTO(Aircraft aircraft) {
        return new AircraftViewDTO(
                aircraft.getRegistrationNumber().toString(),
                aircraft.getAircraftModel().getModelName().name(),
                aircraft.getManufacturingDate(),
                aircraft.getActualSeatingCapacity(),
                aircraft.getStatus().toString(),
                aircraft.getAircraftModel().getModelPhotoUrl(),
                aircraft.getFeatures(),
                aircraft.getAircraftModel().getEngineType()
                , aircraft.getGlobalId(), aircraft.getRevision(), aircraft.getLastModified()
        );
    }

    public OperationalHoursDTO getAircraftOperationalHours(String regNum) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum.trim().toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + regNum));
        return new OperationalHoursDTO(aircraft.getRegistrationNumber().number(), aircraft.getTotalFlightHours());
    }

    public org.springframework.data.domain.Page<OperationalHoursDTO> getAllAircraftOperationalHours(org.springframework.data.domain.Pageable pageable) {
        return aircraftRepository.findAll(pageable)
                .map(aircraft -> new OperationalHoursDTO(aircraft.getRegistrationNumber().number(), aircraft.getTotalFlightHours()));
    }

    public List<TopAircraftModelDTO> getTop5UtilizedModels(String sortBy) {
        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(0, 5);
        return "assignments".equalsIgnoreCase(sortBy) ? aircraftRepository.findTop5ModelsByAssignments(pageRequest) : aircraftRepository.findTop5ModelsByFlightHours(pageRequest);
    }

    public List<TopAircraftModelDTO> getTop5UtilizedAircrafts(String sortBy) {
        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(0, 5);

        if ("hours".equalsIgnoreCase(sortBy)) {
            return aircraftRepository.findTop5AircraftByFlightHours(pageRequest);
        } else {
            return aircraftRepository.findTop5AircraftByAssignments(pageRequest);
        }
    }

    public org.springframework.data.domain.Page<RouteViewDTO> getCompatibleRoutesForAircraft(String regNum, org.springframework.data.domain.Pageable pageable) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum.trim().toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + regNum));

        // Delegate to the Airports & Routes Client
        return airportsClient.getCompatibleRoutes(
                aircraft.getAircraftModel().getMaximumRange(),
                aircraft.getActualSeatingCapacity(),
                pageable);
    }

    public List<AircraftViewDTO> searchAircraftByFeatures(String feature, String engineType) {
        return aircraftRepository.findByFeaturesAndEngine(
                (feature == null || feature.trim().isEmpty()) ? null : feature,
                (engineType == null || engineType.trim().isEmpty()) ? null : engineType
        ).stream().map(this::mapToViewDTO).collect(Collectors.toList());
    }

    public String getRealTimeAircraftStatus(String regNum) {
        Aircraft aircraft = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(regNum.trim().toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + regNum));

        if (aircraft.getStatus() == AircraftStatus.UNDER_MAINTENANCE) return "under maintenance";
        if (aircraft.getStatus() == AircraftStatus.INACTIVE) return "inactive";

        // Delegate to the Flights Service Client
        boolean isInFlight = flightsClient.isAircraftInFlight(regNum);
        return isInFlight ? "in-flight" : "available";
    } // FIXED: Added missing closing brace

    public Double getAircraftRange(String regNum) {
        String reg = regNum.trim().toUpperCase();
        Optional<Aircraft> local = aircraftRepository.findByRegistrationNumber(new RegistrationNumber(reg));
        if (local.isPresent()) return local.get().getAircraftModel().getMaximumRange();
        // Not replicated here (yet): find it on the peers and read the range from the (shared) model.
        AircraftViewDTO remote = peerClient.forwardGetAircraftByRegNum(reg)
                .orElseThrow(() -> new IllegalArgumentException("Aircraft not found: " + regNum));
        return modelRepository.findByModelName(new ModelName(remote.modelName()))
                .orElseThrow(() -> new IllegalArgumentException("Aircraft model not found: " + remote.modelName()))
                .getMaximumRange();
    }
}
