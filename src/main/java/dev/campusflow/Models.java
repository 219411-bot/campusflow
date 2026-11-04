package dev.campusflow;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

final class Models {
    private Models() {}
    record Signup(@NotNull @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "使用 3–32 位字母、数字或下划线") String username,
                  @NotBlank @Size(min = 8, max = 64) String password,
                  @NotBlank @Size(max = 40) String fullName) {}
    record Member(long id, String username, String fullName, String role, boolean verified) {}
    record Event(long id, String title, String category, String description, String location,
                 LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime registrationDeadline,
                 int capacity, int reservedCount, String lifecycle, String cancellationReason) {}
    record EventInput(@NotBlank @Size(max = 100) String title,
                      @Pattern(regexp = "TECH|CAREER|CULTURE|SPORT") @NotNull String category,
                      @NotBlank @Size(max = 2000) String description,
                      @NotBlank @Size(max = 100) String location,
                      @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt,
                      @NotNull LocalDateTime registrationDeadline, @Min(1) @Max(10000) int capacity) {}
    record Page<T>(List<T> items, long total, int page, int size) {}
    record Booking(long id, String status, LocalDateTime registeredAt, LocalDateTime checkedInAt, Event event) {}
    record CheckIn(@NotBlank @Size(max = 16) String code) {}
    record Reason(@NotBlank @Size(max = 300) String reason) {}
    record Verification(@Pattern(regexp = "[a-zA-Z0-9-]{4,32}") @NotNull String campusId) {}
    record CheckinCode(String code, LocalDateTime expiresAt) {}
    record Operation(long id, long eventId, long actorId, Long subjectId, String action, String reason, LocalDateTime occurredAt) {}
    record Attendee(long id, String fullName, String username, String status, LocalDateTime registeredAt, LocalDateTime checkedInAt) {}
    record Stats(long events, long registrations, long attended, long openEvents) {}
}
