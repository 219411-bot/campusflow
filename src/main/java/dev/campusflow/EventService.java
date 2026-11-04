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
import org.springframework.beans.factory.annotation.Value;

@Service
public class EventService {
    private final JdbcTemplate db;
    private final Clock clock;
    private final boolean mysql;
    @Value("${app.require-verification:true}") private boolean requireVerification;
    private static final SecureRandom RANDOM = new SecureRandom();
    public EventService(JdbcTemplate db, Clock clock) {
        this.db = db; this.clock = clock;
        this.mysql = Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equals("MySQL")));
    }

    Member member(String username) {
        return db.query("SELECT id, username, full_name, role, verified_at FROM app_user WHERE username = ?",
            (rs, i) -> new Member(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"), rs.getString("role"), rs.getObject("verified_at") != null), username)
            .stream().findFirst().orElseThrow(() -> new ApiException(401, "账号不存在"));
    }

    Page<Event> events(String search, String category, int page, int size) {
        pagination(page, size);
        if (search.length() > 100) throw new ApiException(400, "搜索参数超出范围");
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " WHERE 1=1";
        var args = new java.util.ArrayList<Object>();
        if (!search.isBlank()) { where += " AND (title LIKE ? ESCAPE '!' OR location LIKE ? ESCAPE '!')"; args.add(pattern); args.add(pattern); }
        if (!category.isBlank()) { where += " AND category = ?"; args.add(category); }
        long total = db.queryForObject("SELECT COUNT(*) FROM campus_event" + where, Long.class, args.toArray());
        args.add(size); args.add(page * size);
        List<Event> items = db.query("SELECT * FROM campus_event" + where + " ORDER BY starts_at DESC, id DESC LIMIT ? OFFSET ?",
            (rs, i) -> mapEvent(rs), args.toArray());
        return new Page<>(items, total, page, size);
    }

    Event event(long id) { return loadEvent(id, false); }

    private Event loadEvent(long id, boolean lock) {
        return loadEvent(id, lock ? " FOR UPDATE" : "");
    }
    private Event loadEvent(long id, String lock) {
        return db.query("SELECT * FROM campus_event WHERE id = ?" + lock, (rs, i) -> mapEvent(rs), id)
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
        long id = key.getKey().longValue();
        audit(id, creator, null, "EVENT_PUBLISHED", "发布活动");
        return id;
    }

    // Every state-changing path locks the event first, so capacity and membership move atomically.
    @Transactional
    public long register(long eventId, long userId) {
        Event event = loadEvent(eventId, true);
        requirePublished(event);
        if (requireVerification && !"ADMIN".equals(db.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, userId))
            && db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ? AND verified_at IS NOT NULL", Integer.class, userId) == 0)
            throw new ApiException(403, "请先由管理员核验校园身份");
        if (!LocalDateTime.now(clock).isBefore(event.registrationDeadline())) throw new ApiException(409, "报名已截止");
        var existing = db.queryForList("SELECT id, status FROM registration WHERE event_id = ? AND user_id = ?", eventId, userId);
        if (!existing.isEmpty() && !"CANCELLED".equals(existing.get(0).get("status"))) throw new ApiException(409, "你已报名该活动");
        if (event.reservedCount() >= event.capacity()) throw new ApiException(409, "名额已满");
        db.update("UPDATE campus_event SET reserved_count = reserved_count + 1 WHERE id = ?", eventId);
        if (!existing.isEmpty()) {
            long id = ((Number) existing.get(0).get("id")).longValue();
            db.update("UPDATE registration SET status = 'REGISTERED', registered_at = ?, checked_in_at = NULL WHERE id = ?", LocalDateTime.now(clock), id);
            audit(eventId, userId, userId, "REREGISTERED", "取消后重新报名");
            return id;
        }
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO registration (event_id, user_id, registered_at) VALUES (?, ?, ?)", new String[]{"id"});
            statement.setLong(1, eventId); statement.setLong(2, userId); statement.setObject(3, LocalDateTime.now(clock));
            return statement;
        }, key);
        long id = key.getKey().longValue();
        audit(eventId, userId, userId, "REGISTERED", "首次报名");
        return id;
    }

    @Transactional
    public void cancel(long eventId, long userId) {
        cancel(eventId, userId, "学生取消报名");
    }

    @Transactional
    public void cancel(long eventId, long userId, String reason) {
        Event event = loadEvent(eventId, true);
        requirePublished(event);
        if (!LocalDateTime.now(clock).isBefore(event.startsAt())) throw new ApiException(409, "活动开始后不可取消");
        int changed = db.update("UPDATE registration SET status = 'CANCELLED' WHERE event_id = ? AND user_id = ? AND status = 'REGISTERED'", eventId, userId);
        if (changed != 1) throw new ApiException(409, "没有可以取消的报名");
        db.update("UPDATE campus_event SET reserved_count = reserved_count - 1 WHERE id = ?", eventId);
        audit(eventId, userId, userId, "CANCELLED", reason);
    }

    @Transactional
    public void checkIn(long eventId, long userId, String code) {
        // Shared event lock permits different attendees to check in concurrently.
        // Event cancellation / code rotation still need the exclusive event lock.
        Event event = loadEvent(eventId, mysql ? " FOR SHARE" : " FOR UPDATE");
        requirePublished(event);
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(event.startsAt().minusMinutes(30)) || now.isAfter(event.endsAt())) throw new ApiException(409, "签到开放时间为活动开始前 30 分钟至活动结束");
        String expected = db.queryForObject("SELECT checkin_code FROM campus_event WHERE id = ?", String.class, eventId);
        LocalDateTime expires = db.queryForObject("SELECT checkin_expires_at FROM campus_event WHERE id = ?", (rs, i) -> time(rs, "checkin_expires_at"), eventId);
        if (expires == null || !now.isBefore(expires)) throw new ApiException(409, "签到码已过期，请向组织者获取新码");
        if (!expected.equals(code.trim().toUpperCase(java.util.Locale.ROOT))) throw new ApiException(400, "签到码错误");
        int changed = db.update("UPDATE registration SET status = 'ATTENDED', checked_in_at = ? WHERE event_id = ? AND user_id = ? AND status = 'REGISTERED'", now, eventId, userId);
        if (changed != 1) throw new ApiException(409, "请先报名，且不可重复签到");
        audit(eventId, userId, userId, "ATTENDED", "短时签到码签到");
    }

    Page<Booking> bookings(long userId, int page, int size) {
        pagination(page, size);
        long total = db.queryForObject("SELECT COUNT(*) FROM registration WHERE user_id = ?", Long.class, userId);
        var items = db.query("SELECT e.*, r.id AS booking_id, r.status AS booking_status, r.registered_at, r.checked_in_at FROM registration r JOIN campus_event e ON e.id = r.event_id WHERE r.user_id = ? ORDER BY r.registered_at DESC, r.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> new Booking(rs.getLong("booking_id"), rs.getString("booking_status"), time(rs, "registered_at"), time(rs, "checked_in_at"), mapEvent(rs)), userId, size, page * size);
        return new Page<>(items, total, page, size);
    }

    java.util.Map<Long, String> bookingStates(long userId, List<Long> ids) {
        if (ids.size() > 50) throw new ApiException(400, "一次最多查询 50 个活动");
        var result = new java.util.HashMap<Long, String>();
        if (ids.isEmpty()) return result;
        var args = new java.util.ArrayList<Object>(); args.add(userId); args.addAll(ids);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        db.query("SELECT event_id, status FROM registration WHERE user_id = ? AND event_id IN (" + placeholders + ")", rs -> {
            result.put(rs.getLong("event_id"), rs.getString("status"));
        }, args.toArray());
        return result;
    }

    Stats stats() {
        return new Stats(db.queryForObject("SELECT COUNT(*) FROM campus_event", Long.class),
            db.queryForObject("SELECT COUNT(*) FROM registration WHERE status <> 'CANCELLED'", Long.class),
            db.queryForObject("SELECT COUNT(*) FROM registration WHERE status = 'ATTENDED'", Long.class),
            db.queryForObject("SELECT COUNT(*) FROM campus_event WHERE status = 'PUBLISHED' AND registration_deadline > ? AND reserved_count < capacity", Long.class, LocalDateTime.now(clock)));
    }

    Page<Attendee> attendees(long eventId, int page, int size) {
        pagination(page, size);
        event(eventId);
        long total = db.queryForObject("SELECT COUNT(*) FROM registration WHERE event_id = ?", Long.class, eventId);
        var items = db.query("SELECT r.id, u.full_name, u.username, r.status, r.registered_at, r.checked_in_at FROM registration r JOIN app_user u ON u.id = r.user_id WHERE r.event_id = ? ORDER BY r.registered_at DESC, r.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> new Attendee(rs.getLong("id"), rs.getString("full_name"), rs.getString("username"), rs.getString("status"), time(rs, "registered_at"), time(rs, "checked_in_at")), eventId, size, page * size);
        return new Page<>(items, total, page, size);
    }

    CheckinCode checkinCode(long id) {
        event(id);
        return db.queryForObject("SELECT checkin_code, checkin_expires_at FROM campus_event WHERE id = ?", (rs, i) -> {
            LocalDateTime expires = time(rs, "checkin_expires_at");
            return new CheckinCode(expires != null && LocalDateTime.now(clock).isBefore(expires) ? rs.getString("checkin_code") : "", expires);
        }, id);
    }

    @Transactional
    public CheckinCode rotateCode(long id, long actor) {
        Event event = loadEvent(id, true); requirePublished(event);
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(event.startsAt().minusMinutes(30)) || now.isAfter(event.endsAt())) throw new ApiException(409, "请在签到窗口内生成签到码");
        LocalDateTime expiry = now.plusMinutes(2).isAfter(event.endsAt()) ? event.endsAt() : now.plusMinutes(2);
        String code = randomCode();
        db.update("UPDATE campus_event SET checkin_code = ?, checkin_expires_at = ? WHERE id = ?", code, expiry, id);
        audit(id, actor, null, "CODE_ROTATED", "签到码有效期不超过两分钟");
        return new CheckinCode(code, expiry);
    }

    @Transactional
    public void cancelEvent(long id, long actor, String reason) {
        Event event = loadEvent(id, true); requirePublished(event);
        if (!LocalDateTime.now(clock).isBefore(event.endsAt())) throw new ApiException(409, "已结束活动不能取消");
        var users = db.queryForList("SELECT user_id FROM registration WHERE event_id = ? AND status = 'REGISTERED'", Long.class, id);
        db.update("UPDATE registration SET status = 'CANCELLED' WHERE event_id = ? AND status = 'REGISTERED'", id);
        db.update("UPDATE campus_event SET status = 'CANCELLED', cancellation_reason = ?, reserved_count = reserved_count - ?, checkin_expires_at = NULL WHERE id = ?", reason, users.size(), id);
        for (long uid : users) audit(id, actor, uid, "EVENT_CANCELLED_BOOKING", reason);
        audit(id, actor, null, "EVENT_CANCELLED", reason);
    }

    @Transactional
    public void verifyIdentity(long userId, String campusId) {
        int changed = db.update("UPDATE app_user SET campus_id = ?, verified_at = ? WHERE id = ? AND role = 'STUDENT' AND campus_id IS NULL", campusId, LocalDateTime.now(clock), userId);
        if (changed == 0) throw new ApiException(409, "用户不存在、非学生或已完成核验");
    }

    Page<Member> members(String search, int page, int size) {
        pagination(page, size);
        if (search.length() > 32) throw new ApiException(400, "用户名过长");
        String where = search.isBlank() ? "" : " WHERE username = ?";
        Object[] args = search.isBlank() ? new Object[]{} : new Object[]{search};
        long total = db.queryForObject("SELECT COUNT(*) FROM app_user" + where, Long.class, args);
        var params = new java.util.ArrayList<Object>(java.util.Arrays.asList(args)); params.add(size); params.add(page * size);
        var items = db.query("SELECT id, username, full_name, role, verified_at FROM app_user" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            (rs, i) -> new Member(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"), rs.getString("role"), rs.getObject("verified_at") != null), params.toArray());
        return new Page<>(items, total, page, size);
    }

    Page<Operation> operations(Long eventId, Long subjectId, int page, int size) {
        pagination(page, size);
        String where = eventId != null ? " WHERE event_id = ?" : " WHERE subject_id = ?";
        Long key = eventId != null ? eventId : subjectId;
        long total = db.queryForObject("SELECT COUNT(*) FROM operation_log" + where, Long.class, key);
        var items = db.query("SELECT * FROM operation_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", (rs, i) ->
            new Operation(rs.getLong("id"), rs.getLong("event_id"), rs.getLong("actor_id"), rs.getObject("subject_id", Long.class), rs.getString("action"), rs.getString("reason"), time(rs, "occurred_at")), key, size, page * size);
        return new Page<>(items, total, page, size);
    }

    private void audit(long eventId, long actor, Long subject, String action, String reason) {
        db.update("INSERT INTO operation_log (event_id, actor_id, subject_id, action, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
            eventId, actor, subject, action, reason, LocalDateTime.now(clock));
    }
    private static void pagination(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 50) throw new ApiException(400, "分页参数超出范围");
    }
    private static void requirePublished(Event event) {
        if ("CANCELLED".equals(event.lifecycle())) throw new ApiException(409, "活动已取消");
    }

    private static String randomCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var out = new StringBuilder();
        for (int i = 0; i < 8; i++) out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return out.toString();
    }
    private Event mapEvent(ResultSet rs) throws SQLException {
        LocalDateTime now = LocalDateTime.now(clock);
        String lifecycle = "CANCELLED".equals(rs.getString("status")) ? "CANCELLED" :
            !now.isBefore(time(rs, "ends_at")) ? "ENDED" : !now.isBefore(time(rs, "starts_at")) ? "ONGOING" :
            !now.isBefore(time(rs, "registration_deadline")) ? "REGISTRATION_CLOSED" : "PUBLISHED";
        return new Event(rs.getLong("id"), rs.getString("title"), rs.getString("category"), rs.getString("description"), rs.getString("location"),
            time(rs, "starts_at"), time(rs, "ends_at"), time(rs, "registration_deadline"), rs.getInt("capacity"), rs.getInt("reserved_count"), lifecycle, rs.getString("cancellation_reason"));
    }
    private static LocalDateTime time(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, LocalDateTime.class);
    }
}
