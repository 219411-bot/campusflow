# HTTP API

基础地址：`http://localhost:8080/api`。JSON 字段使用 camelCase。时间均使用北京时间 `yyyy-MM-dd'T'HH:mm:ss`，前端显示也固定为北京时间。列表参数从 `page=0` 开始，`size` 范围为 1–50。

## 登录方式与请求安全

使用 Spring Security 的服务端 Session。浏览器通过 `HttpOnly` 的 `JSESSIONID` cookie 保持登录；所有写请求均必须附带 CSRF token。登录与退出后需要重新获取 token。

1. `GET /auth/csrf`，保存响应里的 `token`，同时保存 cookie。
2. `POST /auth/login`，Content-Type 为 `application/x-www-form-urlencoded`，字段 `username`、`password`，请求头附带 `X-XSRF-TOKEN`。
3. 再次 `GET /auth/csrf`，后续写请求使用新 token，所有请求携带同一组 cookie。
4. `POST /auth/logout` 使 Session 失效。

这里选择 Session 是因为网页与后端同源，便于撤销登录且无需在 localStorage 中存储长期凭证。不是 JWT 实现。

## 接口清单

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/auth/csrf` | 公开 | 获取写请求 token |
| POST | `/auth/register` | 公开 + CSRF | 注册学生账号 |
| POST | `/auth/login` | 公开 + CSRF | 表单登录 |
| POST | `/auth/logout` | 登录 + CSRF | 退出登录 |
| GET | `/auth/me` | 登录 | 当前用户，不返回密码 |
| GET | `/events?search=&category=&page=0&size=9` | 公开 | 搜索、分类和分页 |
| GET | `/events/{id}` | 公开 | 活动详情，不含签到码 |
| GET | `/stats` | 公开 | 活动数、有效报名数、签到数、开放活动数 |
| GET | `/registrations?page=0&size=20` | 登录 | 仅自己的报名记录，分页，含取消记录 |
| GET | `/registrations/states?eventIds=1,2` | 登录 | 至多 50 个活动的自己的报名状态 |
| GET | `/registrations/history?page=0&size=20` | 登录 | 自己的操作历史，分页 |
| POST | `/events/{id}/registrations` | 登录 + CSRF | 报名，占用一个名额 |
| DELETE | `/events/{id}/registrations` | 登录 + CSRF | 取消自己的报名 |
| POST | `/events/{id}/check-in` | 登录 + CSRF | 通过现场码签到 |
| POST | `/admin/events` | 管理员 + CSRF | 发布活动 |
| GET | `/admin/events/{id}/attendees?page=0&size=20` | 管理员 | 分页报名名单，含取消记录 |
| GET | `/admin/events/{id}/checkin-code` | 管理员 | 获取尚未过期的码和 expiresAt，无有效码时 code 为空 |
| POST | `/admin/events/{id}/checkin-code` | 管理员 + CSRF | 签到窗口内生成两分钟码，立即替换旧码 |
| POST | `/admin/events/{id}/cancel` | 管理员 + CSRF | 取消活动，必须提供 reason |
| GET | `/admin/events/{id}/history?page=0&size=20` | 管理员 | 活动操作流水 |
| GET | `/admin/users?username=alice&page=0&size=20` | 管理员 | 精确用户名查询或分页用户列表，不暴露学号 |
| POST | `/admin/users/{id}/verify` | 管理员 + CSRF | 线下核验后记录唯一校园学号 |

注册请求：

```json
{"username":"alice","password":"Example123!","fullName":"小林"}
```

活动发布请求（日期需替换为未来）：

```json
{
  "title": "Java 技术沙龙",
  "category": "TECH",
  "description": "从请求处理到事务提交，完成一个报名接口。",
  "location": "计算机楼 A203",
  "startsAt": "2030-03-12T14:00:00",
  "endsAt": "2030-03-12T16:00:00",
  "registrationDeadline": "2030-03-11T18:00:00",
  "capacity": 50
}
```

分类：`TECH`、`CAREER`、`CULTURE`、`SPORT`。签到请求：`{"code":"现场提供的8位码"}`。取消原因请求：`{"reason":"课程冲突"}`；学生取消可不传请求体，默认记录「学生取消报名」。核验请求：`{"campusId":"20260001"}`，仅管理员可操作。新账号报名需要管理员已记录核验，演示学生除外。

所有列表统一返回 Page，报名/名单接口由旧数组改为 `items`。活动增加 `lifecycle` 和 `cancellationReason`，用户增加 `verified`，不返回学号。生命周期：`PUBLISHED`、`REGISTRATION_CLOSED`、`ONGOING`、`ENDED`、`CANCELLED`；前四个按业务时间计算，取消持久化保存。

分页响应：

```json
{"items":[],"total":0,"page":0,"size":9}
```

错误统一包含 `message`。状态码：400 参数或签到码错误；401 未登录或会话过期（含过期会话的 CSRF 拒绝）；403 无权限、身份待核验或有效登录会话的 CSRF 失败；404 活动不存在；409 重复报名、满额、过期签到码、时间限制或状态冲突；429 限流（带 Retry-After）；500 服务内部异常（详细信息只写日志）。注册与发布成功返回 201。

## 状态规则

```mermaid
stateDiagram-v2
    [*] --> REGISTERED: 截止前报名且有余量
    REGISTERED --> CANCELLED: 活动开始前取消 / 释放名额
    CANCELLED --> REGISTERED: 截止前再次报名 / 重新占位
    REGISTERED --> ATTENDED: 签到窗口内 + 正确现场码
```

签到窗口为开始前 30 分钟至结束，签到码必须满足 `now < expiresAt`，有效期不超过活动结束。学生取消必须在开始前；管理员可在结束前取消活动，未签到报名统一取消，已签到证据保留。已取消活动不再接受报名或签到。

所有状态更新与 `operation_log` 在同一事务内提交；重报更新当前状态，但旧报名、取消原因、签到等流水不会覆盖。历史按 id 倒序分页。MySQL 签到使用活动共享锁，其他更新使用活动独占锁；然后锁各自报名记录。详见 [性能验证](PERFORMANCE.md) 与 [安全边界](SECURITY.md)。
