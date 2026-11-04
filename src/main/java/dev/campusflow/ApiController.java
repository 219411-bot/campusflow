package dev.campusflow;

import static dev.campusflow.Models.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class ApiController {
    private final EventService events;
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    ApiController(EventService events, JdbcTemplate db, PasswordEncoder encoder) { this.events = events; this.db = db; this.encoder = encoder; }

    @GetMapping("/auth/csrf") Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
    @GetMapping("/auth/me") Member me(Principal principal) { return events.member(principal.getName()); }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> signup(@Valid @RequestBody Signup input) {
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "密码的 UTF-8 编码长度不能超过 72 字节");
        db.update("INSERT INTO app_user (username, password_hash, full_name, role) VALUES (?, ?, ?, 'STUDENT')",
            input.username(), encoder.encode(input.password()), input.fullName().trim());
        return Map.of("message", "注册成功，请登录");
    }
    @GetMapping("/events") Page<Event> list(@RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "9") int size) {
        return events.events(search, category, page, size);
    }
    @GetMapping("/events/{id}") Event detail(@PathVariable long id) { return events.event(id); }
    @GetMapping("/stats") Stats stats() { return events.stats(); }
    @GetMapping("/registrations") Object mine(Principal principal, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return events.bookings(events.member(principal.getName()).id(), page, size);
    }
    @GetMapping("/registrations/states") Object states(Principal principal, @RequestParam java.util.List<Long> eventIds) {
        return events.bookingStates(events.member(principal.getName()).id(), eventIds);
    }
    @GetMapping("/registrations/history") Object history(Principal principal, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return events.operations(null, events.member(principal.getName()).id(), page, size);
    }
    @PostMapping("/events/{id}/registrations") @ResponseStatus(HttpStatus.CREATED)
    Object register(@PathVariable long id, Principal principal) {
        return Map.of("id", events.register(id, events.member(principal.getName()).id()), "message", "报名成功");
    }
    @DeleteMapping("/events/{id}/registrations") Object cancel(@PathVariable long id, Principal principal, @Valid @RequestBody(required = false) Reason input) {
        events.cancel(id, events.member(principal.getName()).id(), input == null ? "学生取消报名" : input.reason()); return Map.of("message", "报名已取消，名额已释放");
    }
    @PostMapping("/events/{id}/check-in") Object checkIn(@PathVariable long id, @Valid @RequestBody CheckIn input, Principal principal) {
        events.checkIn(id, events.member(principal.getName()).id(), input.code()); return Map.of("message", "签到成功");
    }
    @PostMapping("/admin/events") @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody EventInput input, Principal principal) {
        return Map.of("id", events.create(input, events.member(principal.getName()).id()), "message", "活动发布成功");
    }
    @GetMapping("/admin/events/{id}/attendees") Object attendees(@PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return events.attendees(id, page, size); }
    @GetMapping("/admin/events/{id}/checkin-code") Object code(@PathVariable long id) { return events.checkinCode(id); }
    @PostMapping("/admin/events/{id}/checkin-code") Object rotate(@PathVariable long id, Principal principal) { return events.rotateCode(id, events.member(principal.getName()).id()); }
    @PostMapping("/admin/events/{id}/cancel") Object cancelEvent(@PathVariable long id, @Valid @RequestBody Reason reason, Principal principal) {
        events.cancelEvent(id, events.member(principal.getName()).id(), reason.reason()); return Map.of("message", "活动已取消，未签到报名已取消，历史记录已保留");
    }
    @GetMapping("/admin/events/{id}/history") Object audit(@PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return events.operations(id, null, page, size); }
    @GetMapping("/admin/users") Object users(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return events.members(username, page, size); }
    @PostMapping("/admin/users/{id}/verify") Object verify(@PathVariable long id, @Valid @RequestBody Verification input) {
        events.verifyIdentity(id, input.campusId()); return Map.of("message", "校园身份核验已记录");
    }
}
