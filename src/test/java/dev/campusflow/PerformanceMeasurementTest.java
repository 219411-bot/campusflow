package dev.campusflow;

import static dev.campusflow.Models.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Explicit opt-in. Use a disposable MySQL database whose name ends with _test. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_PERF", matches = "true")
class PerformanceMeasurementTest {
    @Autowired JdbcTemplate db;
    @Autowired EventService service;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;

    @Test void measureContentionAndExplainQueries() throws Exception {
        String url = db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getURL());
        assertThat(url).startsWith("jdbc:mysql:").contains("_test");
        assertThat(db.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (!"true".equals(System.getenv("PERF_REUSE"))) {
        db.update("DELETE FROM operation_log"); db.update("DELETE FROM registration"); db.update("DELETE FROM campus_event"); db.update("DELETE FROM app_user");
        // Fixture passwords are deliberately unusable. No HTTP accounts are created.
        for (int offset = 0; offset < 2000; offset += 500) {
            var rows = new ArrayList<Object[]>();
            for (int i = offset; i < offset + 500; i++) rows.add(new Object[]{i + 1, "perf" + i, "!disabled", "Perf", "STUDENT", "PERF" + i, LocalDateTime.now(clock)});
            db.batchUpdate("INSERT INTO app_user(id, username, password_hash, full_name, role, campus_id, verified_at) VALUES(?,?,?,?,?,?,?)", rows);
        }
        for (int offset = 0; offset < 20000; offset += 500) {
            var rows = new ArrayList<Object[]>();
            for (int i = offset; i < offset + 500; i++) rows.add(new Object[]{i + 1, "Activity " + i, i % 4 == 0 ? "TECH" : i % 4 == 1 ? "CAREER" : i % 4 == 2 ? "CULTURE" : "SPORT", "Fixture", "Room " + i, now.plusDays(i % 365 + 1), now.plusDays(i % 365 + 1).plusHours(2), now.plusHours(1), 10000, "PERF1234", 1});
            db.batchUpdate("INSERT INTO campus_event(id,title,category,description,location,starts_at,ends_at,registration_deadline,capacity,checkin_code,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)", rows);
        }
        for (int offset = 0; offset < 50000; offset += 500) {
            var rows = new ArrayList<Object[]>();
            for (int i = offset; i < offset + 500; i++) rows.add(new Object[]{i / 2000 + 1, i % 2000 + 1, now.minusSeconds(i)});
            db.batchUpdate("INSERT INTO registration(event_id,user_id,registered_at) VALUES(?,?,?)", rows);
        }
        db.update("UPDATE campus_event SET reserved_count = (SELECT COUNT(*) FROM registration WHERE event_id = campus_event.id)");
        } else {
            assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username LIKE 'perf%'", Integer.class)).isEqualTo(2000);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM campus_event WHERE id <= 20000", Integer.class)).isEqualTo(20000);
        }
        db.execute("ANALYZE TABLE campus_event"); db.execute("ANALYZE TABLE registration");
        var plans = new LinkedHashMap<String,Object>();
        plans.put("allEvents", explain("SELECT * FROM campus_event ORDER BY starts_at DESC, id DESC LIMIT 20"));
        plans.put("category", explain("SELECT * FROM campus_event WHERE category = 'TECH' ORDER BY starts_at DESC, id DESC LIMIT 20"));
        plans.put("containsSearch", explain("SELECT * FROM campus_event WHERE title LIKE '%199%' OR location LIKE '%199%' ORDER BY starts_at DESC, id DESC LIMIT 20"));
        plans.put("containsSearchCount", explain("SELECT COUNT(*) FROM campus_event WHERE title LIKE '%199%' OR location LIKE '%199%'"));
        plans.put("bookings", explain("SELECT e.*, r.status FROM registration r JOIN campus_event e ON e.id = r.event_id WHERE r.user_id = 1 ORDER BY r.registered_at DESC, r.id DESC LIMIT 20"));
        plans.put("attendees", explain("SELECT r.*, u.full_name FROM registration r JOIN app_user u ON u.id = r.user_id WHERE r.event_id = 1 ORDER BY r.registered_at DESC, r.id DESC LIMIT 20"));
        var runs = new ArrayList<Object>();
        // A warm-up event is excluded from timed samples.
        long warm = service.create(new EventInput("Warmup", "TECH", "Warmup", "Here", now.plusHours(1), now.plusHours(2), now.plusMinutes(30), 10000), 1);
        for (int i = 1; i <= 100; i++) service.register(warm, i);
        for (int concurrency : new int[]{1, 8, 32}) {
            long id = service.create(new EventInput("Register benchmark", "TECH", "Fixture", "Here", now.plusHours(1), now.plusHours(2), now.plusMinutes(30), 10000), 1);
            runs.add(measure("registration", concurrency, 400, uid -> service.register(id, uid)));
            assertThat(db.queryForObject("SELECT reserved_count FROM campus_event WHERE id = ?", Integer.class, id)).isEqualTo(400);
        }
        for (int concurrency : new int[]{1, 8, 32}) {
            long id = service.create(new EventInput("Checkin benchmark", "TECH", "Fixture", "Here", now.plusMinutes(20), now.plusHours(2), now.plusMinutes(10), 10000), 1);
            for (int i = 1; i <= 400; i++) service.register(id, i);
            String code = service.rotateCode(id, 1).code();
            runs.add(measure("checkin_shared_event_lock", concurrency, 400, uid -> service.checkIn(id, uid, code)));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM registration WHERE event_id = ? AND status = 'ATTENDED'", Integer.class, id)).isEqualTo(400);
        }
        var report = new LinkedHashMap<String,Object>();
        report.put("measuredAt", Instant.now().toString()); report.put("scope", "In-process transactional service benchmark; excludes HTTP, authentication and rate limiter; single local hot event per run");
        report.put("java", System.getProperty("java.version")); report.put("os", System.getProperty("os.name")); report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("database", db.queryForObject("SELECT VERSION()", String.class)); report.put("poolSize",20);
        report.put("reusedFixture", "true".equals(System.getenv("PERF_REUSE")));
        report.put("dataset", Map.of("users",2000,"events",20000,"registrations",50000)); report.put("runs",runs); report.put("explainAnalyze", plans);
        Files.createDirectories(Path.of("docs/performance"));
        Files.writeString(Path.of("docs/performance/mysql-local.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private String explain(String sql) { return db.queryForObject("EXPLAIN ANALYZE " + sql, String.class); }
    private long lockStat(String name) {
        return db.query("SHOW GLOBAL STATUS LIKE ?", (rs,i)->Long.parseLong(rs.getString(2)), name).get(0);
    }
    private Map<String,Object> measure(String name, int concurrency, int requests, java.util.function.LongConsumer work) throws Exception {
        long waits = lockStat("Innodb_row_lock_waits"), lockMs = lockStat("Innodb_row_lock_time");
        var pool = Executors.newFixedThreadPool(concurrency); var futures = new ArrayList<Future<Long>>();
        long start = System.nanoTime();
        try {
            for (int i = 1; i <= requests; i++) { final long uid = i;
                futures.add(pool.submit(()->{ long before = System.nanoTime(); work.accept(uid); return System.nanoTime()-before; }));
            }
            var timings = new ArrayList<Long>(); for (var f:futures) timings.add(f.get(90,TimeUnit.SECONDS));
            double seconds = (System.nanoTime()-start)/1e9; Collections.sort(timings);
            return Map.of("operation",name,"concurrency",concurrency,"requests",requests,"success",timings.size(),
                "seconds",seconds,"throughputPerSecond",requests/seconds,"p95Ms",timings.get((int)Math.ceil(requests*.95)-1)/1e6,
                "p50Ms",timings.get(requests/2)/1e6,"rowLockWaitCountDelta",lockStat("Innodb_row_lock_waits")-waits,"rowLockWaitMsDelta",lockStat("Innodb_row_lock_time")-lockMs);
        } finally { pool.shutdownNow(); }
    }
}
