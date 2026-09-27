package com.example.ungdunggoixe.service;

import com.example.ungdunggoixe.dto.request.CreateBookingRequest;
import com.example.ungdunggoixe.entity.Station;
import com.example.ungdunggoixe.entity.User;
import com.example.ungdunggoixe.entity.Vehicle;
import com.example.ungdunggoixe.exception.AppException;
import com.example.ungdunggoixe.exception.ErrorCode;
import com.example.ungdunggoixe.repository.BookingRepository;
import com.example.ungdunggoixe.repository.StationRepository;
import com.example.ungdunggoixe.repository.UserRepository;
import com.example.ungdunggoixe.repository.VehicleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nhiều request đặt cùng một xe, cùng khung giờ, gửi đồng thời:
 * chỉ đúng 1 booking được tạo, các request còn lại nhận VEHICLE_NOT_AVAILABLE.
 */
@SpringBootTest(properties = {
        // H2 mặc định chờ khóa 1s; nới ra để các request xếp hàng chờ khóa dòng xe thay vì timeout.
        "spring.datasource.url=jdbc:h2:mem:booking_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
class BookingConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private UserRepository userRepository;

    private User renter;
    private Station station;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        station = new Station();
        station.setName("Tram test");
        station.setAddress("1 Test Street");
        station = stationRepository.save(station);

        vehicle = new Vehicle();
        vehicle.setStation(station);
        vehicle.setLicensePlate("TEST-" + System.nanoTime() % 1_000_000_000);
        vehicle.setName("Test car");
        vehicle.setHourlyRate(new BigDecimal("100000"));
        vehicle.setDailyRate(new BigDecimal("1000000"));
        vehicle = vehicleRepository.save(vehicle);

        renter = new User();
        renter.setEmail("renter-" + System.nanoTime() + "@example.com");
        renter.setFirstName("Test");
        renter.setLastName("Renter");
        renter = userRepository.save(renter);
    }

    @AfterEach
    void tearDown() {
        bookingRepository.deleteAll(bookingRepository.findAll().stream()
                .filter(b -> b.getVehicle().getId().equals(vehicle.getId()))
                .toList());
        vehicleRepository.delete(vehicle);
        stationRepository.delete(station);
        userRepository.delete(renter);
    }

    @Test
    void concurrentOverlappingBookings_onlyOneSucceeds() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        LocalDateTime end = start.plusDays(2);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Throwable>> results = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            // Mỗi request lệch giờ một chút nhưng vẫn chồng lên nhau.
            LocalDateTime s = start.plusHours(i);
            LocalDateTime e = end.plusHours(i);
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    bookingService.createBooking(request(s, e));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<Throwable> f : results) {
            Throwable t = f.get(30, TimeUnit.SECONDS);
            if (t == null) {
                succeeded++;
            } else {
                assertThat(t).isInstanceOf(AppException.class);
                assertThat(((AppException) t).getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_AVAILABLE);
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(CONCURRENT_REQUESTS - 1);
        long saved = bookingRepository.findAll().stream()
                .filter(b -> b.getVehicle().getId().equals(vehicle.getId()))
                .count();
        assertThat(saved).isEqualTo(1);
    }

    private CreateBookingRequest request(LocalDateTime start, LocalDateTime end) {
        CreateBookingRequest r = new CreateBookingRequest();
        r.setRenterId(renter.getId());
        r.setVehicleId(vehicle.getId());
        r.setStationId(station.getId());
        r.setStartTime(start);
        r.setExpectedEndTime(end);
        return r;
    }
}
