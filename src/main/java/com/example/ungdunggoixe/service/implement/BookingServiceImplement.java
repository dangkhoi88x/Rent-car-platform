package com.example.ungdunggoixe.service.implement;

import com.example.ungdunggoixe.service.*;

import com.example.ungdunggoixe.common.BookingStatus;
import com.example.ungdunggoixe.exception.ErrorCode;
import com.example.ungdunggoixe.common.PaymentStatus;
import com.example.ungdunggoixe.common.StationStatus;
import com.example.ungdunggoixe.common.VehicleStatus;
import com.example.ungdunggoixe.constant.sort.BookingSortField;
import com.example.ungdunggoixe.dto.request.BookingPageRequest;
import com.example.ungdunggoixe.dto.request.CreateBookingRequest;
import com.example.ungdunggoixe.dto.request.UpdateBookingRequest;
import com.example.ungdunggoixe.dto.response.BookingResponse;
import com.example.ungdunggoixe.dto.response.PageResponse;
import com.example.ungdunggoixe.entity.Booking;
import com.example.ungdunggoixe.entity.Payment;
import com.example.ungdunggoixe.entity.Station;
import com.example.ungdunggoixe.entity.User;
import com.example.ungdunggoixe.entity.Vehicle;
import com.example.ungdunggoixe.exception.AppException;
import com.example.ungdunggoixe.mapper.BookingMapper;
import com.example.ungdunggoixe.repository.BookingRepository;
import com.example.ungdunggoixe.repository.PaymentRepository;
import com.example.ungdunggoixe.repository.StationRepository;
import com.example.ungdunggoixe.repository.UserRepository;
import com.example.ungdunggoixe.repository.VehicleRepository;
import com.example.ungdunggoixe.repository.specification.BookingSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingServiceImplement implements BookingService {
    /** Tối thiểu cọc (tiền mặt đã vào) so với tổng booking trước khi PENDING → CONFIRMED. */
    private static final BigDecimal MIN_DEPOSIT_RATE = new BigDecimal("0.10");

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final StationRepository stationRepository;
    private final PaymentRepository paymentRepository;
    private final I18nService i18nService;

    /** Các trạng thái booking được coi là "đang chiếm xe" */
    private static final List<BookingStatus> ACTIVE_STATUSES = List.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.ONGOING
    );

    private static String mapBookingSortProperty(String sortBy) {
        return BookingSortField.toEntityPath(sortBy);
    }

    private static Specification<Booking> buildBookingSpec(
            Long renterId,
            Long stationId,
            Long vehicleId,
            BookingStatus status,
            PaymentStatus paymentStatus,
            LocalDateTime startTimeFrom,
            LocalDateTime startTimeTo,
            LocalDateTime createdAtFrom,
            LocalDateTime createdAtTo,
            String keyword
    ) {
        Long safeRenterId = renterId != null && renterId > 0 ? renterId : null;
        Long safeStationId = stationId != null && stationId > 0 ? stationId : null;
        Long safeVehicleId = vehicleId != null && vehicleId > 0 ? vehicleId : null;

        return BookingSpecs.alwaysTrue()
                .and(BookingSpecs.renterIdEquals(safeRenterId))
                .and(BookingSpecs.stationIdEquals(safeStationId))
                .and(BookingSpecs.vehicleIdEquals(safeVehicleId))
                .and(BookingSpecs.statusEquals(status))
                .and(BookingSpecs.paymentStatusEquals(paymentStatus))
                .and(BookingSpecs.startTimeFrom(startTimeFrom))
                .and(BookingSpecs.startTimeTo(startTimeTo))
                .and(BookingSpecs.createdAtFrom(createdAtFrom))
                .and(BookingSpecs.createdAtTo(createdAtTo))
                .and(BookingSpecs.keywordContains(keyword));
    }

    // ═══════════════════════════════════════════════════════
    // 1. TẠO BOOKING (có kiểm tra xe trống theo time range)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        validateCreateRequest(request);

        User renter = userRepository.findById(request.getRenterId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        // Khóa dòng xe tới hết transaction: request đặt cùng xe phải chờ, nên check trùng lịch + insert là nguyên tử.
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(request.getVehicleId())
                .orElseThrow(() -> new AppException(ErrorCode.VEHICLE_NOT_FOUND));
        Station station = stationRepository.findById(request.getStationId())
                .orElseThrow(() -> new AppException(ErrorCode.STATION_NOT_FOUND));

        // ── Kiểm tra trạm có đang hoạt động không ──
        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(ErrorCode.STATION_INACTIVE);
        }

        // ── Kiểm tra xe có sẵn (chống double booking) ──
        checkVehicleAvailability(vehicle.getId(), request.getStartTime(), request.getExpectedEndTime());

        // ── Kiểm tra trạng thái vật lý của xe ──
        if (vehicle.getStatus() == VehicleStatus.MAINTENANCE
                || vehicle.getStatus() == VehicleStatus.UNAVAILABLE) {
            throw new AppException(ErrorCode.VEHICLE_NOT_IN_CORRECT_STATUS);
        }

        Booking booking = BookingMapper.INSTANCE.toBooking(request);
        booking.setRenter(renter);
        booking.setVehicle(vehicle);
        booking.setStation(station);
        booking.setBookingCode(generateBookingCode());
        booking.setStatus(BookingStatus.PENDING);
        booking.setPaymentStatus(PaymentStatus.PENDING);
        // Gán tạm renter để insert hợp lệ; pickup (có JWT nhân viên) sẽ ghi đè.
        booking.setCheckedOutBy(renter);

        BigDecimal basePrice = calculateBasePrice(vehicle, request.getStartTime(), request.getExpectedEndTime());
        booking.setBasePrice(basePrice);
        booking.setExtraFee(BigDecimal.ZERO);
        booking.setPartiallyPaid(BigDecimal.ZERO);
        booking.setTotalAmount(basePrice);

        Booking saved = bookingRepository.save(booking);
        return BookingMapper.INSTANCE.toBookingResponse(saved);
    }

    // ═══════════════════════════════════════════════════════
    // 2. XÁC NHẬN BOOKING (PENDING → CONFIRMED)
    // ═══════════════════════════════════════════════════════

    @Transactional
    public BookingResponse confirmBooking(Long id) {
        Booking booking = findBookingById(id);

        validateStatusTransition(booking.getStatus(), BookingStatus.CONFIRMED);
        assertDepositCollected(booking);

        booking.setStatus(BookingStatus.CONFIRMED);

        Booking saved = bookingRepository.save(booking);
        return BookingMapper.INSTANCE.toBookingResponse(saved);
    }

    // ═══════════════════════════════════════════════════════
    // 3. GIAO XE (CONFIRMED → ONGOING), Vehicle → RENTED
    // ═══════════════════════════════════════════════════════

    @Transactional
    public BookingResponse pickupBooking(Long id) {
        Booking booking = findBookingById(id);

        validateStatusTransition(booking.getStatus(), BookingStatus.ONGOING);

        settleOutstandingCashAtPickup(booking);

        // Chuyển trạng thái booking → ONGOING
        booking.setStatus(BookingStatus.ONGOING);
        resolveCurrentUser().ifPresent(booking::setCheckedOutBy);

        // Chuyển trạng thái xe → RENTED
        Vehicle vehicle = booking.getVehicle();
        vehicle.setStatus(VehicleStatus.RENTED);
        vehicleRepository.save(vehicle);

        Booking saved = bookingRepository.save(booking);
        return BookingMapper.INSTANCE.toBookingResponse(saved);
    }

    // ═══════════════════════════════════════════════════════
    // 4. TRẢ XE (ONGOING → COMPLETED), Vehicle → AVAILABLE
    // ═══════════════════════════════════════════════════════

    @Transactional
    public BookingResponse returnBooking(Long id) {
        Booking booking = findBookingById(id);

        validateStatusTransition(booking.getStatus(), BookingStatus.COMPLETED);

        LocalDateTime now = LocalDateTime.now();
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setActualEndTime(now);
        resolveCurrentUser().ifPresent(booking::setCheckedInBy);

        // Tính phí trả trễ (nếu có)
        if (now.isAfter(booking.getExpectedEndTime())) {
            BigDecimal extraFee = calculateLateFee(booking.getVehicle(), booking.getExpectedEndTime(), now);
            booking.setExtraFee(extraFee);
        } else if (booking.getExtraFee() == null) {
            booking.setExtraFee(BigDecimal.ZERO);
        }
        BigDecimal base = booking.getBasePrice() == null ? BigDecimal.ZERO : booking.getBasePrice();
        BigDecimal extra = booking.getExtraFee() == null ? BigDecimal.ZERO : booking.getExtraFee();
        BigDecimal total = base.add(extra).setScale(2, RoundingMode.HALF_UP);
        booking.setTotalAmount(total);

        // Chuyển trạng thái xe → AVAILABLE
        Vehicle vehicle = booking.getVehicle();
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        // Tăng số lần cho thuê
        vehicle.setRentCount(vehicle.getRentCount() == null ? 1 : vehicle.getRentCount() + 1);
        vehicleRepository.save(vehicle);

        createSettlementAdjustmentPaymentIfNeeded(booking);

        Booking saved = bookingRepository.save(booking);
        return BookingMapper.INSTANCE.toBookingResponse(saved);
    }

    // ═══════════════════════════════════════════════════════
    // 5. HỦY BOOKING (PENDING/CONFIRMED → CANCELLED)
    //    Nếu đang ONGOING → cũng cho hủy, xe → AVAILABLE
    // ═══════════════════════════════════════════════════════

    @Transactional
    public BookingResponse cancelBooking(Long id) {
        Booking booking = findBookingById(id);

        validateStatusTransition(booking.getStatus(), BookingStatus.CANCELLED);

        // Nếu xe đang ở trạng thái RENTED (booking đang ONGOING), trả xe về AVAILABLE
        if (booking.getStatus() == BookingStatus.ONGOING) {
            Vehicle vehicle = booking.getVehicle();
            vehicle.setStatus(VehicleStatus.AVAILABLE);
            vehicleRepository.save(vehicle);
        }

        booking.setStatus(BookingStatus.CANCELLED);

        Booking saved = bookingRepository.save(booking);
        return BookingMapper.INSTANCE.toBookingResponse(saved);
    }

    // ═══════════════════════════════════════════════════════
    // 6. KIỂM TRA XE CÓ SẴN THEO TIME RANGE (public API)
    // ═══════════════════════════════════════════════════════

    /**
     * Kiểm tra xe có sẵn trong khoảng thời gian [start, end] hay không.
     * @return true nếu xe có sẵn (không bị trùng booking)
     */
    public boolean isVehicleAvailable(Long vehicleId, LocalDateTime start, LocalDateTime end) {
        return !bookingRepository.existsOverlappingBooking(vehicleId, ACTIVE_STATUSES, start, end);
    }

    // ═══════════════════════════════════════════════════════
    // 7. LỊCH SỬ BOOKING CỦA USER (GET /me/bookings)
    // ═══════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(Long userId) {
        List<Booking> bookings = bookingRepository.findByRenterId(userId);
        return bookings.stream()
                .map(BookingMapper.INSTANCE::toBookingResponse)
                .toList();
    }

    // ═══════════════════════════════════════════════════════
    // CÁC API ĐỌC / CẬP NHẬT CƠ BẢN (giữ nguyên)
    // ═══════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long id) {
        Booking booking = findBookingById(id);
        return BookingMapper.INSTANCE.toBookingResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookings(Long renterId, Long stationId, BookingStatus status) {
        Specification<Booking> spec = buildBookingSpec(
                renterId,
                stationId,
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                null
        );
        List<Booking> bookings = bookingRepository.findAll(spec);
        return bookings.stream()
                .map(BookingMapper.INSTANCE::toBookingResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getBookingsPaged(BookingPageRequest request) {
        BookingPageRequest safeRequest = request == null ? new BookingPageRequest() : request;
        String field = mapBookingSortProperty(safeRequest.getSortBy());
        Sort.Direction direction = "asc".equalsIgnoreCase(safeRequest.getSortDir())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        int safeSize = Math.min(Math.max(safeRequest.getSize(), 1), 100);
        int safePage = Math.max(safeRequest.getPage(), 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, field));

        Specification<Booking> spec = buildBookingSpec(
                safeRequest.getRenterId(),
                safeRequest.getStationId(),
                safeRequest.getVehicleId(),
                safeRequest.getStatus(),
                safeRequest.getPaymentStatus(),
                safeRequest.getStartTimeFrom(),
                safeRequest.getStartTimeTo(),
                safeRequest.getCreatedAtFrom(),
                safeRequest.getCreatedAtTo(),
                safeRequest.getKeyword()
        );

        Page<Booking> result = bookingRepository.findAll(spec, pageable);

        Page<BookingResponse> mapped = result.map(BookingMapper.INSTANCE::toBookingResponse);
        return PageResponse.<BookingResponse>builder()
                .content(mapped.getContent())
                .totalElements(mapped.getTotalElements())
                .totalPages(mapped.getTotalPages())
                .page(mapped.getNumber())
                .size(mapped.getSize())
                .build();
    }

    @Transactional
    public BookingResponse updateBooking(Long id, UpdateBookingRequest request) {
        if (request == null) {
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }
        if (request.getStartTime() != null && request.getExpectedEndTime() != null
                && !request.getExpectedEndTime().isAfter(request.getStartTime())) {
            throw new AppException(ErrorCode.BOOKING_TIME_INVALID);
        }

        Booking booking = findBookingById(id);

        BookingMapper.INSTANCE.updateEntity(request, booking);

        if (booking.getExtraFee() == null) {
            booking.setExtraFee(BigDecimal.ZERO);
        }
        if (booking.getPartiallyPaid() == null) {
            booking.setPartiallyPaid(BigDecimal.ZERO);
        }
        if (booking.getTotalAmount() == null) {
            BigDecimal total = booking.getBasePrice() == null ? BigDecimal.ZERO : booking.getBasePrice();
            booking.setTotalAmount(total.add(booking.getExtraFee()));
        }

        Booking updated = bookingRepository.save(booking);
        return BookingMapper.INSTANCE.toBookingResponse(updated);
    }

    @Transactional
    public String deleteBooking(Long id) {
        Booking booking = findBookingById(id);
        // Chỉ cho phép xóa booking ở trạng thái PENDING hoặc CANCELLED
        if (booking.getStatus() != BookingStatus.PENDING
                && booking.getStatus() != BookingStatus.CANCELLED) {
            throw new AppException(ErrorCode.BOOKING_STATUS_TRANSITION_INVALID);
        }
        bookingRepository.delete(booking);
        return i18nService.getMessage("response.booking.delete.success");
    }

    // ═══════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════

    private Booking findBookingById(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));
    }

    /** JWT subject = user id (cùng convention với {@code UserService#getMyInfo}). */
    private Optional<User> resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            Long id = Long.parseLong(auth.getName());
            return userRepository.findById(id);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Kiểm tra xe có bị trùng lịch không. Nếu có → ném exception.
     */
    private void checkVehicleAvailability(Long vehicleId, LocalDateTime start, LocalDateTime end) {
        boolean hasConflict = bookingRepository.existsOverlappingBooking(
                vehicleId, ACTIVE_STATUSES, start, end
        );
        if (hasConflict) {
            throw new AppException(ErrorCode.VEHICLE_NOT_AVAILABLE);
        }
    }

    /**
     * Validate cho phép chuyển trạng thái booking.
     * Các luồng hợp lệ:
     *   PENDING    → CONFIRMED, CANCELLED
     *   CONFIRMED  → ONGOING,   CANCELLED
     *   ONGOING    → COMPLETED, CANCELLED
     */
    /**
     * Đã thu đủ cọc tại trạm: {@code partiallyPaid} (tổng PAID) ≥ 10% {@code totalAmount}.
     */
    private void assertDepositCollected(Booking booking) {
        // Với luồng trả tại trạm (cash), booking có thể chưa có payment record nào.
        // Trong trường hợp này không chặn confirm; staff thu tiền tại quầy rồi giao xe.
        List<Payment> payments = paymentRepository.findByBookingId(booking.getId());
        if (payments.isEmpty()) {
            return;
        }

        BigDecimal total = booking.getTotalAmount() == null ? BigDecimal.ZERO : booking.getTotalAmount();
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal minDeposit = total.multiply(MIN_DEPOSIT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = booking.getPartiallyPaid() == null ? BigDecimal.ZERO : booking.getPartiallyPaid();
        if (paid.compareTo(minDeposit) < 0) {
            throw new AppException(ErrorCode.BOOKING_DEPOSIT_REQUIRED);
        }
    }

    private void validateStatusTransition(BookingStatus current, BookingStatus target) {
        boolean valid = switch (current) {
            case PENDING   -> target == BookingStatus.CONFIRMED || target == BookingStatus.CANCELLED;
            case CONFIRMED -> target == BookingStatus.ONGOING   || target == BookingStatus.CANCELLED;
            case ONGOING   -> target == BookingStatus.COMPLETED || target == BookingStatus.CANCELLED;
            default        -> false;
        };
        if (!valid) {
            throw new AppException(ErrorCode.BOOKING_STATUS_TRANSITION_INVALID);
        }
    }

    private void validateCreateRequest(CreateBookingRequest request) {
        if (request == null || request.getRenterId() == null || request.getStartTime() == null
                || request.getExpectedEndTime() == null) {
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }
        if (!request.getExpectedEndTime().isAfter(request.getStartTime())) {
            throw new AppException(ErrorCode.BOOKING_TIME_INVALID);
        }
    }

    private String generateBookingCode() {
        String bookingCode = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if (bookingRepository.existsByBookingCode(bookingCode)) {
            return generateBookingCode();
        }
        return bookingCode;
    }

    private BigDecimal calculateBasePrice(Vehicle vehicle, LocalDateTime startTime, LocalDateTime expectedEndTime) {
        long durationMillis = Duration.between(startTime, expectedEndTime).toMillis();
        long rentalDays = Math.max(1, (long) Math.ceil(durationMillis / (24d * 60 * 60 * 1000)));
        BigDecimal dailyRate = vehicle.getDailyRate() == null ? BigDecimal.ZERO : vehicle.getDailyRate();
        return dailyRate.multiply(BigDecimal.valueOf(rentalDays));
    }

    /**
     * Tính phí trả trễ = số giờ trễ × hourlyRate
     */
    private BigDecimal calculateLateFee(Vehicle vehicle, LocalDateTime expectedEnd, LocalDateTime actualEnd) {
        long lateHours = Math.max(1, Duration.between(expectedEnd, actualEnd).toHours());
        BigDecimal hourlyRate = vehicle.getHourlyRate() == null ? BigDecimal.ZERO : vehicle.getHourlyRate();
        return hourlyRate.multiply(BigDecimal.valueOf(lateHours));
    }

    private void createSettlementAdjustmentPaymentIfNeeded(Booking booking) {
        BigDecimal total = booking.getTotalAmount() == null ? BigDecimal.ZERO : booking.getTotalAmount();
        BigDecimal paid = booking.getPartiallyPaid() == null ? BigDecimal.ZERO : booking.getPartiallyPaid();
        BigDecimal delta = total.subtract(paid).setScale(2, RoundingMode.HALF_UP);

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            paymentRepository.save(Payment.builder()
                    .booking(booking)
                    .amount(delta)
                    .paymentMethod(Payment.PaymentMethod.CASH)
                    .paymentPurpose(Payment.PaymentPurpose.TOPUP)
                    .status(Payment.PaymentStatus.PENDING)
                    .transactionId("TOPUP_AUTO_BOOKING_" + booking.getId() + "_" + System.currentTimeMillis())
                    .build());
            return;
        }

        if (delta.compareTo(BigDecimal.ZERO) < 0) {
            paymentRepository.save(Payment.builder()
                    .booking(booking)
                    .amount(delta.abs())
                    .paymentMethod(Payment.PaymentMethod.CASH)
                    .paymentPurpose(Payment.PaymentPurpose.REFUND)
                    .status(Payment.PaymentStatus.PENDING)
                    .transactionId("REFUND_AUTO_BOOKING_" + booking.getId() + "_" + System.currentTimeMillis())
                    .build());
        }
    }

    /**
     * Khi staff giao xe tại trạm, coi như đã thu phần còn thiếu bằng tiền mặt.
     * Điều này giúp lần "trả xe" không sinh thêm TOPUP treo do chênh lệch chưa thu.
     */
    private void   settleOutstandingCashAtPickup(Booking booking) {
        BigDecimal total = booking.getTotalAmount() == null ? BigDecimal.ZERO : booking.getTotalAmount();
        BigDecimal paid = booking.getPartiallyPaid() == null ? BigDecimal.ZERO : booking.getPartiallyPaid();
        BigDecimal remaining = total.subtract(paid).setScale(2, RoundingMode.HALF_UP);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        Payment cashCollectedAtPickup = Payment.builder()
                .booking(booking)
                .amount(remaining)
                .paymentMethod(Payment.PaymentMethod.CASH)
                .paymentPurpose(Payment.PaymentPurpose.TOPUP)
                .status(Payment.PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .transactionId("PICKUP_CASH_BOOKING_" + booking.getId() + "_" + System.currentTimeMillis())
                .build();
        resolveCurrentUser().ifPresent(cashCollectedAtPickup::setProcessedBy);
        paymentRepository.save(cashCollectedAtPickup);

        booking.setPartiallyPaid(total);
        booking.setPaymentStatus(PaymentStatus.PAID);
    }
}
