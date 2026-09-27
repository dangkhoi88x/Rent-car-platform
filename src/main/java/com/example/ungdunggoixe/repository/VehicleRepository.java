package com.example.ungdunggoixe.repository;

import com.example.ungdunggoixe.common.VehicleStatus;
import com.example.ungdunggoixe.entity.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle,Long>, JpaSpecificationExecutor<Vehicle> {
    boolean existsByLicensePlate(String licensePlate);
    boolean existsByLicensePlateAndIdNot(String licensePlate, Long id);
    long countByStatus(VehicleStatus status);

    /**
     * SELECT ... FOR UPDATE trên dòng xe: tuần tự hóa các giao dịch đặt cùng một xe,
     * để bước kiểm tra trùng lịch và insert booking không bị chen ngang (chống double booking).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vehicle v WHERE v.id = :id")
    Optional<Vehicle> findByIdForUpdate(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = "station")
    Page<Vehicle> findAll(org.springframework.data.jpa.domain.Specification<Vehicle> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "station")
    List<Vehicle> findAll(org.springframework.data.jpa.domain.Specification<Vehicle> spec);

    @Override
    @EntityGraph(attributePaths = "station")
    List<Vehicle> findAll(org.springframework.data.jpa.domain.Specification<Vehicle> spec, org.springframework.data.domain.Sort sort);
}
