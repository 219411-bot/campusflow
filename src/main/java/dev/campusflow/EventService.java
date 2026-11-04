package dev.campusflow;

import static dev.campusflow.Models.*;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final JdbcTemplate db;
    private final Clock clock;
    private static final SecureRandom RANDOM = new SecureRandom();
    public EventService(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

    Member member(String username) {
        return db.query("SELECT id, username, full_name, role FROM app_user WHERE username = ?",
            (rs, i) -> new Member(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"), rs.getString("role")), username)
            .stream().findFirst().orElseThrow(() -> new ApiException(401, "账号不存在"));
    }

    Page<Event> events(String search, String category, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 50 || search.length() > 100) throw new ApiException(400, "分页或搜索参数超出范围");
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " WHERE (title LIKE ? ESCAPE '!' OR location LIKE ? ESCAPE '!') AND (? = '' OR category = ?)";
        long total = db.queryForObject("SELECT COUNT(*) FROM campus_event" + where, Long.class, pattern, pattern, category, category);
        List<Event> items = db.query("SELECT * FROM campus_event" + where + " ORDER BY starts_at DESC, id DESC LIMIT ? OFFSET ?",
            (rs, i) -> mapEvent(rs), pattern, pattern, category, category, size, page * size);
        return new Page<>(items, total, page, size);
    }

    Event event(long id) { return loadEvent(id, false); }

    private Event loadEvent(long id, boolean lock) {
        return db.query("SELECT * FROM campus_event WHERE id = ?" + (lock ? " FOR UPDATE" : ""), (rs, i) -> mapEvent(rs), id)
            .stream().findFirst().orElseThrow(() -> new ApiException(404, "活动不存在"));
    }

    @Transactional
    public long create(EventInput input, long creator) {
        if (!input.endsAt().isAfter(input.startsAt()) || input.registrationDeadline().isAfter(input.startsAt())
                || !input.registrationDeadline().isAfter(LocalDateTime.now(clock))) throw new ApiException(400, "请检查活动起止时间和报名截止时间");
        String code = randomCode();
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO campus_event (title, category, description, location, starts_at, ends_at, registration_deadline, capacity, checkin_code, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[]{"id"});
            statement.setString(1, input.title().trim()); statement.setString(2, input.category());
            statement.setString(3, input.description().trim()); statement.setString(4, input.location().trim());
            statement.setObject(5, input.startsAt()); statement.setObject(6, input.endsAt());
            statement.setObject(7, input.registrationDeadline()); statement.setInt(8, input.capacity());
            statement.setString(9, code); statement.setLong(10, creator);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    // Every state-changing path locks the event first, so capacity and membership move atomically.
    @Transactional
    public long register(long eventId, long userId) {
        Event event = loadEvent(eventId, true);
        if (!LocalDateTime.now(clock).isBefore(event.registrationDeadline())) throw new ApiException(409, "报名已截止");
        var existing = db.queryForList("SELECT id, status FROM registration WHERE event_id = ? AND user_id = ?", eventId, userId);
        if (!existing.isEmpty() && !"CANCELLED".equals(existing.get(0).get("status"))) throw new ApiException(409, "你已报名该活动");
        if (event.reservedCount() >= event.capacity()) throw new ApiException(409, "名额已满");
        db.update("UPDATE campus_event SET reserved_count = reserved_count + 1 WHERE id = ?", eventId);
        if (!existing.isEmpty()) {
            long id = ((Number) existing.get(0).get("id")).longValue();
            db.update("UPDATE registration SET status = 'REGISTERED', registered_at = ?, checked_in_at = NULL WHERE id = ?", LocalDateTime.now(clock), id);
            return id;
        }
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO registration (event_id, user_id, registered_at) VALUES (?, ?, ?)", new String[]{"id"});
            statement.setLong(1, eventId); statement.setLong(2, userId); statement.setObject(3, LocalDateTime.now(clock));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    @Transactional
    public void cancel(long eventId, long userId) {
        Event event = loadEvent(eventId, true);
        if (!LocalDateTime.now(clock).isBefore(event.startsAt())) throw new ApiException(409, "活动开始后不可取消");
        int changed = db.update("UPDATE registration SET status = 'CANCELLED' WHERE event_id = ? AND user_id = ? AND status = 'REGISTERED'", eventId, userId);
        if (changed != 1) throw new ApiException(409, "没有可以取消的报名");
        db.update("UPDATE campus_event SET reserved_count = reserved_count - 1 WHERE id = ?", eventId);
    }

    @Transactional
    public void checkIn(long eventId, long userId, String code) {
        Event event = loadEvent(eventId, true);
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(event.startsAt().minusMinutes(30)) || now.isAfter(event.endsAt())) throw new ApiException(409, "签到开放时间为活动开始前 30 分钟至活动结束");
        String expected = db.queryForObject("SELECT checkin_code FROM campus_event WHERE id = ?", String.class, eventId);
        if (!expected.equals(code.trim().toUpperCase(java.util.Locale.ROOT))) throw new ApiException(400, "签到码错误");
        int changed = db.update("UPDATE registration SET status = 'ATTENDED', checked_in_at = ? WHERE event_id = ? AND user_id = ? AND status = 'REGISTERED'", now, eventId, userId);
        if (changed != 1) throw new ApiException(409, "请先报名，且不可重复签到");
    }

    List<Booking> bookings(long userId) {
        return db.query("SELECT e.*, r.id AS booking_id, r.status, r.registered_at, r.checked_in_at FROM registration r JOIN campus_event e ON e.id = r.event_id WHERE r.user_id = ? ORDER BY r.registered_at DESC",
            (rs, i) -> new Booking(rs.getLong("booking_id"), rs.getString("status"), time(rs, "registered_at"), time(rs, "checked_in_at"), mapEvent(rs)), userId);
    }

    Stats stats() {
        return new Stats(db.queryForObject("SELECT COUNT(*) FROM campus_event", Long.class),
            db.queryForObject("SELECT COUNT(*) FROM registration WHERE status <> 'CANCELLED'", Long.class),
            db.queryForObject("SELECT COUNT(*) FROM registration WHERE status = 'ATTENDED'", Long.class),
            db.queryForObject("SELECT COUNT(*) FROM campus_event WHERE registration_deadline > ? AND reserved_count < capacity", Long.class, LocalDateTime.now(clock)));
    }

    List<Attendee> attendees(long eventId) {
        event(eventId);
        return db.query("SELECT r.id, u.full_name, u.username, r.status, r.registered_at, r.checked_in_at FROM registration r JOIN app_user u ON u.id = r.user_id WHERE r.event_id = ? ORDER BY r.registered_at DESC",
            (rs, i) -> new Attendee(rs.getLong("id"), rs.getString("full_name"), rs.getString("username"), rs.getString("status"), time(rs, "registered_at"), time(rs, "checked_in_at")), eventId);
    }

    String checkinCode(long id) {
        event(id);
        return db.queryForObject("SELECT checkin_code FROM campus_event WHERE id = ?", String.class, id);
    }

    private static String randomCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var out = new StringBuilder();
        for (int i = 0; i < 8; i++) out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return out.toString();
    }
    private static Event mapEvent(ResultSet rs) throws SQLException {
        return new Event(rs.getLong("id"), rs.getString("title"), rs.getString("category"), rs.getString("description"), rs.getString("location"),
            time(rs, "starts_at"), time(rs, "ends_at"), time(rs, "registration_deadline"), rs.getInt("capacity"), rs.getInt("reserved_count"));
    }
    private static LocalDateTime time(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, LocalDateTime.class);
    }
}
