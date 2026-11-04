package dev.campusflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static dev.campusflow.Models.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired EventService service;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    long admin, student;
    static final LocalDateTime NOW = LocalDateTime.of(2030, 3, 10, 12, 0);

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai")); }
    }

    @BeforeEach void setup() {
        db.update("DELETE FROM registration"); db.update("DELETE FROM campus_event"); db.update("DELETE FROM app_user");
        admin = addUser("admin", "ADMIN"); student = addUser("student", "STUDENT");
    }

    long addUser(String name, String role) {
        db.update("INSERT INTO app_user (username, password_hash, full_name, role) VALUES (?, ?, ?, ?)", name, encoder.encode("Test12345!"), name, role);
        return db.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, name);
    }
    long event(int capacity) { return service.create(input(capacity), admin); }
    EventInput input(int capacity) { return new EventInput("Java 技术交流", "TECH", "学习事务和数据库", "实验室 301", NOW.plusDays(2), NOW.plusDays(2).plusHours(2), NOW.plusDays(1), capacity); }
    int reserved(long id) { return db.queryForObject("SELECT reserved_count FROM campus_event WHERE id = ?", Integer.class, id); }
    String bookingStatus(long id, long user) { return db.queryForObject("SELECT status FROM registration WHERE event_id = ? AND user_id = ?", String.class, id, user); }

    @Test void publicListSupportsSearchCategoryPaginationAndNoCheckinLeak() throws Exception {
        event(5); event(8);
        mvc.perform(get("/api/events").param("search", "Java").param("category", "TECH").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].checkinCode").doesNotExist());
        mvc.perform(get("/api/events").param("search", "%' OR 1=1 --")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/events").param("size", "1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events/9999999")).andExpect(status().isNotFound());
    }

    @Test void signupHashesPasswordsAndCannotGrantAdminRole() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"Strong123!\",\"fullName\":\"Alice\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isCreated());
        String hash = db.queryForObject("SELECT password_hash FROM app_user WHERE username = 'alice'", String.class);
        assertThat(hash).isNotEqualTo("Strong123!"); assertThat(encoder.matches("Strong123!", hash)).isTrue();
        assertThat(service.member("alice").role()).isEqualTo("STUDENT");
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"Strong123!\",\"fullName\":\"Alice\"}"))
            .andExpect(status().isConflict());
    }

    @Test void invalidInputsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content("{\"username\":null,\"password\":\"123\",\"fullName\":\" \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/events").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType("application/json").content("{\"capacity\":-1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/events/not-a-number")).andExpect(status().isBadRequest());
    }

    @Test void realSessionLoginWrongPasswordLogoutAndCsrfProtection() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "student").param("password", "wrong"))
            .andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "student").param("password", "Test12345!"))
            .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("student"));
        mvc.perform(post("/api/events/1/registrations").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void permissionsProtectAdminAndUserRecords() throws Exception {
        long id = event(5); service.register(id, student);
        mvc.perform(get("/api/registrations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/events/" + id + "/checkin-code").with(user("student").roles("STUDENT")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/events").with(user("student").roles("STUDENT")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input(5))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/registrations").with(user("admin").roles("ADMIN"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/registrations").with(user("student").roles("STUDENT"))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/events/" + id + "/registrations").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isConflict());
        assertThat(reserved(id)).isEqualTo(1);
    }

    @Test void adminCanPublishAndRetrieveAttendeesAndPrivateCode() throws Exception {
        mvc.perform(post("/api/admin/events").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(input(10))))
            .andExpect(status().isCreated());
        long id = db.queryForObject("SELECT id FROM campus_event", Long.class);
        mvc.perform(post("/api/events/" + id + "/registrations").with(user("student")).with(csrf())).andExpect(status().isCreated());
        mvc.perform(get("/api/admin/events/" + id + "/attendees").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("student"));
        mvc.perform(get("/api/admin/events/" + id + "/checkin-code").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").isNotEmpty());
    }

    @Test void duplicateRegistrationAndFullCapacityRollbackWithoutCountDrift() {
        long id = event(1); long another = addUser("another", "STUDENT");
        service.register(id, student);
        assertThatThrownBy(() -> service.register(id, student)).isInstanceOf(ApiException.class).hasMessageContaining("已报名");
        assertThatThrownBy(() -> service.register(id, another)).isInstanceOf(ApiException.class).hasMessageContaining("已满");
        assertThat(reserved(id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM registration WHERE event_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test void cancellationReleasesSeatAndReregistrationReusesRecord() {
        long id = event(1); long first = service.register(id, student);
        service.cancel(id, student); assertThat(reserved(id)).isZero(); assertThat(bookingStatus(id, student)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.cancel(id, student)).isInstanceOf(ApiException.class);
        assertThat(reserved(id)).isZero();
        assertThat(service.register(id, student)).isEqualTo(first);
        assertThat(reserved(id)).isEqualTo(1); assertThat(bookingStatus(id, student)).isEqualTo("REGISTERED");
    }

    @Test void deadlineAndInvalidSchedulePreventRegistrations() {
        long id = event(3);
        db.update("UPDATE campus_event SET registration_deadline = ? WHERE id = ?", NOW, id);
        assertThatThrownBy(() -> service.register(id, student)).isInstanceOf(ApiException.class).hasMessageContaining("截止");
        assertThat(reserved(id)).isZero();
        var invalid = new EventInput("Test", "TECH", "Test", "Here", NOW.plusDays(1), NOW, NOW.plusHours(1), 5);
        assertThatThrownBy(() -> service.create(invalid, admin)).isInstanceOf(ApiException.class);
    }

    @Test void checkinEnforcesTimeCodeRegistrationAndSingleTransition() {
        long id = event(4); service.register(id, student);
        String code = service.checkinCode(id);
        assertThatThrownBy(() -> service.checkIn(id, student, code)).hasMessageContaining("开放时间");
        db.update("UPDATE campus_event SET registration_deadline = ?, starts_at = ?, ends_at = ? WHERE id = ?", NOW.minusHours(1), NOW.plusMinutes(20), NOW.plusHours(2), id);
        assertThatThrownBy(() -> service.checkIn(id, student, "BADCODE")).hasMessageContaining("错误");
        assertThatThrownBy(() -> service.checkIn(id, admin, code)).hasMessageContaining("先报名");
        service.checkIn(id, student, code.toLowerCase(java.util.Locale.ROOT));
        assertThat(bookingStatus(id, student)).isEqualTo("ATTENDED");
        assertThatThrownBy(() -> service.checkIn(id, student, code)).hasMessageContaining("重复");
        assertThatThrownBy(() -> service.cancel(id, student)).isInstanceOf(ApiException.class);
        assertThat(service.stats().attended()).isEqualTo(1);
        assertThat(reserved(id)).isEqualTo(1);
        db.update("UPDATE campus_event SET ends_at = ?, starts_at = ?, registration_deadline = ? WHERE id = ?", NOW.minusMinutes(1), NOW.minusHours(2), NOW.minusHours(3), id);
        assertThatThrownBy(() -> service.checkIn(id, student, code)).hasMessageContaining("开放时间");
    }

    @Test void thirtyConcurrentRequestsForFiveSeatsNeverOversell() throws Exception {
        long id = event(5);
        var users = new ArrayList<Long>();
        for (int i = 0; i < 30; i++) users.add(addUser("concurrent" + i, "STUDENT"));
        var results = race(users, uid -> service.register(id, uid));
        assertThat(results.stream().filter(result -> result == 201).count()).isEqualTo(5);
        assertThat(results.stream().filter(result -> result == 409).count()).isEqualTo(25);
        assertThat(reserved(id)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM registration WHERE event_id = ? AND status = 'REGISTERED'", Integer.class, id)).isEqualTo(5);
    }

    @Test void concurrentDuplicateRequestsCreateExactlyOneBooking() throws Exception {
        long id = event(20);
        var users = java.util.Collections.nCopies(12, student);
        var results = race(users, uid -> service.register(id, uid));
        assertThat(results.stream().filter(result -> result == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result == 409).count()).isEqualTo(11);
        assertThat(reserved(id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM registration WHERE event_id = ?", Integer.class, id)).isEqualTo(1);
    }

    private java.util.List<Integer> race(java.util.List<Long> users, java.util.function.LongConsumer work) throws Exception {
        var pool = Executors.newFixedThreadPool(users.size());
        var start = new CountDownLatch(1); var ready = new CountDownLatch(users.size());
        var futures = new ArrayList<Future<Integer>>();
        try {
            for (long uid : users) futures.add(pool.submit(() -> {
                ready.countDown(); if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try { work.accept(uid); return 201; } catch (ApiException e) { return e.status; }
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results = new ArrayList<Integer>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }
}
