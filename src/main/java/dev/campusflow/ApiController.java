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
    @GetMapping("/registrations") Object mine(Principal principal) { return events.bookings(events.member(principal.getName()).id()); }
    @PostMapping("/events/{id}/registrations") @ResponseStatus(HttpStatus.CREATED)
    Object register(@PathVariable long id, Principal principal) {
        return Map.of("id", events.register(id, events.member(principal.getName()).id()), "message", "报名成功");
    }
    @DeleteMapping("/events/{id}/registrations") Object cancel(@PathVariable long id, Principal principal) {
        events.cancel(id, events.member(principal.getName()).id()); return Map.of("message", "报名已取消，名额已释放");
    }
    @PostMapping("/events/{id}/check-in") Object checkIn(@PathVariable long id, @Valid @RequestBody CheckIn input, Principal principal) {
        events.checkIn(id, events.member(principal.getName()).id(), input.code()); return Map.of("message", "签到成功");
    }
    @PostMapping("/admin/events") @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody EventInput input, Principal principal) {
        return Map.of("id", events.create(input, events.member(principal.getName()).id()), "message", "活动发布成功");
    }
    @GetMapping("/admin/events/{id}/attendees") Object attendees(@PathVariable long id) { return events.attendees(id); }
    @GetMapping("/admin/events/{id}/checkin-code") Object code(@PathVariable long id) { return Map.of("code", events.checkinCode(id)); }
}
