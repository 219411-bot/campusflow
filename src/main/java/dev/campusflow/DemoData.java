package dev.campusflow;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
class DemoData implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final Clock clock;
    DemoData(JdbcTemplate db, PasswordEncoder encoder, Clock clock) { this.db = db; this.encoder = encoder; this.clock = clock; }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (db.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) > 0) return;
        db.update("INSERT INTO app_user (username, password_hash, full_name, role) VALUES (?, ?, ?, ?)", "admin", encoder.encode("Admin123!"), "活动管理员", "ADMIN");
        db.update("INSERT INTO app_user (username, password_hash, full_name, role) VALUES (?, ?, ?, ?)", "student", encoder.encode("Student123!"), "林同学", "STUDENT");
        db.update("UPDATE app_user SET campus_id = 'DEMO20260001', verified_at = ? WHERE username = 'student'", LocalDateTime.now(clock));
        long admin = db.queryForObject("SELECT id FROM app_user WHERE username = 'admin'", Long.class);
        LocalDateTime now = LocalDateTime.now(clock).withSecond(0).withNano(0);
        add(admin, "从校园到大厂：Java 后端技术沙龙", "TECH", "从一个 HTTP 请求出发，拆解 Spring Boot、MySQL 事务与并发控制。带上你的电脑，现场完成一个可以运行的报名接口。", "图书馆 · 创新工坊 301", now.plusDays(2).withHour(14), 120);
        add(admin, "简历门诊 · 让项目经历更有说服力", "CAREER", "和学长学姐一起打磨项目描述，练习用问题、方案和验证结果讲清楚自己的贡献。欢迎携带一页简历参与。", "就业指导中心 · 204", now.plusDays(3).withHour(15), 40);
        add(admin, "城市漫游：校园摄影工作坊", "CULTURE", "用镜头记录熟悉的校园。从构图、光线到叙事，和摄影社一起完成一组属于你的校园影像。手机也可以参加。", "艺术中心 · 一楼展厅", now.plusDays(5).withHour(10), 30);
        add(admin, "周末一起动起来 · 羽毛球友谊赛", "SPORT", "面向所有水平的同学开放，自由组队、轻松交流。请穿着运动服，自备球拍，现场提供羽毛球。", "体育馆 · 羽毛球 2 号场", now.plusDays(6).withHour(9), 32);
        add(admin, "开源入门：你的第一个 Pull Request", "TECH", "从阅读贡献指南到提交一个小修复，走完 Git 分支、代码评审和持续集成的完整流程。", "计算机楼 · 实验室 A203", now.plusDays(8).withHour(19), 60);
        add(admin, "正在进行 · 产品与技术圆桌", "CAREER", "一场开放的小型圆桌，聊聊实习中的协作与成长。演示账号已报名，请管理员在活动名单页面生成两分钟签到码。", "学生中心 · 共享客厅", now.minusMinutes(10), 20);
        long event = db.queryForObject("SELECT id FROM campus_event WHERE title = '正在进行 · 产品与技术圆桌'", Long.class);
        long student = db.queryForObject("SELECT id FROM app_user WHERE username = 'student'", Long.class);
        db.update("INSERT INTO registration (event_id, user_id) VALUES (?, ?)", event, student);
        db.update("UPDATE campus_event SET reserved_count = 1 WHERE id = ?", event);
    }
    private void add(long admin, String title, String category, String description, String location, LocalDateTime start, int capacity) {
        db.update("INSERT INTO campus_event (title, category, description, location, starts_at, ends_at, registration_deadline, capacity, checkin_code, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            title, category, description, location, start, start.plusHours(2), start.minusHours(1), capacity, "CAMPUS88", admin);
    }
}
