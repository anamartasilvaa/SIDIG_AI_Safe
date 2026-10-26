package pt.isep.psoft.aisafe.aircraft.repositories;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import pt.isep.psoft.aisafe.aircraft.domain.AircraftModel;
import pt.isep.psoft.aisafe.aircraft.domain.ModelName;
import java.util.Optional;

@Repository
public interface AircraftModelRepository extends CrudRepository<AircraftModel, Long> {


    Optional<AircraftModel> findByModelName(ModelName modelName);
}
