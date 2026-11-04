# 验证记录

验证日期：2026-11-04（北京时间）。

| 验证 | 环境 | 结果 |
| --- | --- | --- |
| `mvn verify` | Java 17 + H2 内存数据库 | 12 项测试通过，打包成功 |
| `mvn verify` | Java 17 + 独立 MySQL 8.0.46 测试库 | 12 项测试通过，打包成功 |
| UTC JVM 时区回归 | MySQL 8.0.46 + 北京时间连接 | 业务时间使用 DATETIME / JDBC LocalDateTime，验证跨时区签到判断 |
| 并发名额一致性 | 30 用户同时报名 5 个名额 | 5 次成功，25 次冲突，无超卖 |
| 并发重复报名 | 同一用户同时请求 12 次 | 仅 1 次成功，1 条报名，计数为 1 |
| 浏览器交互 | Edge / Playwright，1440px 桌面 | 分类筛选、登录退出、报名、取消、错误/正确签到、管理员发布与查看名单通过 |
| 手机布局 | 390 × 844 viewport | 无页面横向溢出，已生成截图 |
| JavaScript | `node --check` | 通过，浏览器无未捕获脚本错误 |
| Compose 配置 | `docker compose --env-file .env.example config --quiet` | 配置解析通过 |

Docker Desktop 引擎在本地验证时没有运行，所以未实际执行 `docker compose up`；真实 MySQL 验证使用隔离的 3308 端口实例，没有修改已有 MySQL 服务。CI 配置进一步在 MySQL 8.4 上运行同一套测试，执行结果以仓库 Actions 页面为准。

并发测试检查状态一致性，不提供生产环境吞吐量或延迟保证。截图来自实际运行界面。
