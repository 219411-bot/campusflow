'use strict';
const $ = (selector) => document.querySelector(selector);
const categories = {TECH: ['技术交流', 'TECH &\nIDEAS', '⌘'], CAREER: ['职业成长', 'GROW &\nCONNECT', '↗'], CULTURE: ['文化艺术', 'ART &\nSTORIES', '✳'], SPORT: ['运动健康', 'MOVE &\nENERGY', '◎']};
const statuses = {REGISTERED: '已报名', CANCELLED: '已取消', ATTENDED: '已签到'};
let csrf = '', user = null, bookings = [], allEvents = [], page = 0, adminPage = 0, category = '', search = '', currentView = 'discover', authMode = 'login';
let toastTimer, searchTimer, requestVersion = 0;
let minePage = 0, mineTotal = 0, bookingStates = {}, attendeePage = 0, attendeeEvent = 0, sessionRevision = 0, pendingView = null;
const lifecycleLabels = {PUBLISHED:'报名开放', REGISTRATION_CLOSED:'报名截止', ONGOING:'进行中', ENDED:'已结束', CANCELLED:'活动已取消'};
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const date = (value) => value ? new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false}) : '—';
// Server date-times are Asia/Shanghai wall time, regardless of the browser's location.
const eventTime = (value) => new Date(value + '+08:00').getTime();
const displayDate = (value) => value ? date(new Date(eventTime(value)).toISOString()) : '—';
function toast(message, error = false) { const el = $('#toast'); el.textContent = message; el.className = 'toast' + (error ? ' error' : ''); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.add('hidden'), 4200); }
async function refreshCsrf() {
  const revision = sessionRevision;
  const res = await fetch('/api/auth/csrf'); if (!res.ok) throw new Error('无法获取安全令牌');
  const data = await res.json(); if (revision === sessionRevision) csrf = data.token;
}
async function api(path, options = {}) {
  const revision = sessionRevision;
  const method = options.method || 'GET';
  const headers = {...options.headers};
  if (method !== 'GET') { if (!csrf) await refreshCsrf(); headers['X-XSRF-TOKEN'] = csrf; }
  let body = options.body;
  if (body && !(body instanceof URLSearchParams)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  const res = await fetch('/api' + path, {...options, headers, body});
  const data = await res.json().catch(() => ({}));
  if (revision !== sessionRevision && !path.startsWith('/auth/')) {
    const stale = new Error('登录状态已变化，请重新加载'); stale.status = 401; throw stale;
  }
  if (!res.ok) {
    if (res.status === 401 && user && path !== '/auth/login') expireSession();
    const error = new Error(data.message || '请求失败，请稍后重试'); error.status = res.status; throw error;
  }
  return data;
}
function empty(title, text) { return `<div class="empty"><strong>${escapeHtml(title)}</strong>${escapeHtml(text)}</div>`; }
function openModal(html) { $('#modal-content').innerHTML = html; if (!$('#modal').open) $('#modal').showModal(); }
function closeModal() { $('#modal').close(); }
function syncAccount() {
  $('#account').innerHTML = user ? `<span class="avatar">${escapeHtml(user.fullName.slice(0, 1))}</span><span class="account-name">${escapeHtml(user.fullName)}</span><button class="text-btn" data-action="logout">退出</button>` : '<button class="btn btn-dark small" data-action="login">登录 / 注册 ↗</button>';
  $('#admin-nav').classList.toggle('hidden', user?.role !== 'ADMIN');
}
async function loadStats() {
  const data = await api('/stats');
  $('#stats').innerHTML = [['活动总数', data.events, 'EVENTS'], ['有效报名', data.registrations, 'CONNECTIONS'], ['已签到人次', data.attended, 'CHECK-INS'], ['正在招募', data.openEvents, 'OPEN NOW']].map(([label, n, en]) => `<div class="stat"><span>${label}</span><strong>${n}</strong><small>${en}</small></div>`).join('');
}
async function loadBookings() {
  const revision = sessionRevision;
  if (!user) { bookings = []; mineTotal = 0; return; }
  const data = await api('/registrations?' + new URLSearchParams({page:minePage,size:20}));
  if (revision !== sessionRevision || !user) return;
  bookings = data.items; mineTotal = data.total;
}
function expireSession() {
  pendingView = currentView; sessionRevision++; requestVersion++;
  user = null; csrf = ''; bookings = []; bookingStates = {}; mineTotal = 0;
  $('#bookings').innerHTML = ''; $('#admin-events').innerHTML = ''; $('#admin-pagination').innerHTML = '';
  syncAccount(); currentView = 'discover';
  document.querySelectorAll('.view').forEach(el => el.classList.toggle('hidden', el.id !== 'discover-view'));
  document.querySelectorAll('[data-view]').forEach(el => el.classList.toggle('active', el.dataset.view === 'discover'));
  $('#breadcrumb').textContent = '发现活动';
  allEvents.length && ($('#events').innerHTML = allEvents.map(card).join(''));
  showAuth(); toast('登录已过期，请重新登录', true);
}
function pager(prefix, current, total, size) {
  return total > size ? `<div class="pagination"><button class="btn btn-outline small" data-action="${prefix}-prev" ${current===0?'disabled':''}>← 上一页</button><span>${current+1} / ${Math.ceil(total/size)}</span><button class="btn btn-outline small" data-action="${prefix}-next" ${(current+1)*size>=total?'disabled':''}>下一页 →</button></div>` : '';
}
function card(event) {
  const [label, english, symbol] = categories[event.category] || categories.TECH;
  const booked = bookingStates[event.id] && bookingStates[event.id] !== 'CANCELLED' ? {status:bookingStates[event.id]} : null;
  const expired = Date.now() >= eventTime(event.registrationDeadline);
  const full = event.reservedCount >= event.capacity;
  const state = event.lifecycle === 'CANCELLED' ? '活动已取消' : booked ? statuses[booked.status] : expired ? '报名截止' : full ? '名额已满' : '查看活动 ↗';
  return `<article class="event-card"><div class="event-visual ${escapeHtml(event.category)}"><span class="visual-type">${english.replace('\n', '<br>')}</span><span class="visual-symbol">${symbol}</span><span class="visual-line"></span><span class="event-number">CAMPUS EXPERIENCE / ${String(event.id).padStart(3, '0')}</span></div><div class="event-body"><span class="category-tag">${label}</span><h3>${escapeHtml(event.title)}</h3><p class="meta"><span>◷</span>${escapeHtml(displayDate(event.startsAt))}</p><p class="meta"><span>⌖</span>${escapeHtml(event.location)}</p><div class="event-bottom"><div class="capacity"><b>${event.reservedCount}</b> / ${event.capacity} 人报名<div class="progress"><i style="width:${Math.min(100, event.reservedCount / event.capacity * 100)}%"></i></div></div><button class="text-btn ${expired || full ? 'closed' : ''}" data-action="detail" data-id="${event.id}">${state}</button></div></div></article>`;
}
async function loadEvents() {
  const version = ++requestVersion;
  $('#events').innerHTML = '<div class="loading">正在寻找有趣的活动…</div>';
  try {
    const data = await api('/events?' + new URLSearchParams({page, size: 9, category, search}));
    if (version !== requestVersion) return;
    allEvents = data.items;
    bookingStates = user && data.items.length ? await api('/registrations/states?eventIds=' + data.items.map(e=>e.id).join(',')) : {};
    if (version !== requestVersion) return;
    $('#result-count').textContent = `${data.total} 个活动`;
    $('#events').innerHTML = data.items.map(card).join('') || empty('还没有找到活动', '试试其他关键词或活动类型。');
    $('#pagination').innerHTML = data.total > 9 ? `<button class="btn btn-outline small" data-action="prev" ${page === 0 ? 'disabled' : ''}>← 上一页</button><span>${page + 1} / ${Math.ceil(data.total / 9)}</span><button class="btn btn-outline small" data-action="next" ${(page + 1) * 9 >= data.total ? 'disabled' : ''}>下一页 →</button>` : '';
  } catch (e) { if (version === requestVersion) $('#events').innerHTML = empty('活动加载失败', e.message); throw e; }
}
async function showView(view) {
  if (view === 'mine' && !user) { showAuth(); return; }
  if (view === 'admin' && user?.role !== 'ADMIN') return;
  currentView = view;
  document.querySelectorAll('.view').forEach(el => el.classList.add('hidden'));
  $(`#${view}-view`).classList.remove('hidden');
  document.querySelectorAll('[data-view]').forEach(el => el.classList.toggle('active', el.dataset.view === view));
  $('#breadcrumb').textContent = {discover: '发现活动', mine: '我的报名', admin: '活动管理'}[view];
  if (view === 'mine') { await loadBookings(); renderBookings(); }
  if (view === 'admin') await loadAdmin();
}
function showAuth(mode = 'login') {
  authMode = mode;
  const register = mode === 'register';
  openModal(`<p class="modal-eyebrow">WELCOME TO CAMPUSFLOW</p><h2>${register ? '开启你的校园新故事' : '很高兴，在这里遇见你'}</h2><form id="auth-form" class="form-stack">${register ? '<label>你的名字<input name="fullName" required maxlength="40" autocomplete="name" placeholder="同学怎么称呼？"></label><p class="form-note">注册后需由管理员核验校园身份，才能报名活动。</p>' : ''}<label>用户名<input name="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_]{3,32}" autocomplete="username" placeholder="字母、数字或下划线"></label><label>密码<input name="password" type="password" required minlength="8" maxlength="64" autocomplete="${register ? 'new-password' : 'current-password'}" placeholder="至少 8 位字符"></label><p id="form-error" class="form-error" role="alert"></p><button type="submit" class="btn btn-dark">${register ? '创建账号' : '登录'} ↗</button></form><p class="switch-auth">${register ? '已有账号？' : '还没有账号？'}<button data-action="switch-auth">${register ? '去登录' : '立即注册'}</button></p>`);
}
async function showDetail(id) {
  const event = await api('/events/' + id);
  const states = user ? await api('/registrations/states?eventIds=' + id) : {};
  const b = states[id] && states[id] !== 'CANCELLED' ? {status:states[id]} : null;
  const disabled = event.lifecycle === 'CANCELLED' || Date.now() >= eventTime(event.registrationDeadline) || event.reservedCount >= event.capacity;
  openModal(`<p class="modal-eyebrow">${categories[event.category]?.[0] || '校园活动'} / CAMPUS EXPERIENCE</p><h2>${escapeHtml(event.title)}</h2><div class="detail-meta"><p>◷ ${escapeHtml(displayDate(event.startsAt))} — ${escapeHtml(displayDate(event.endsAt))}</p><p>⌖ ${escapeHtml(event.location)}</p><p>报名截止：${escapeHtml(displayDate(event.registrationDeadline))}</p><p>${event.reservedCount} / ${event.capacity} 人 · 时间均为北京时间</p></div><p class="detail-description">${escapeHtml(event.description)}</p><p>${lifecycleLabels[event.lifecycle] || ''}${event.cancellationReason ? ' · ' + escapeHtml(event.cancellationReason) : ''}</p><div class="detail-actions">${b ? `<span class="status ${b.status}">${statuses[b.status]}</span><button class="btn btn-outline" data-action="go-mine">查看我的报名 ↗</button>` : `<button class="btn btn-dark" data-action="register" data-id="${id}" ${disabled ? 'disabled' : ''}>${disabled ? '报名已截止或名额已满' : user ? '报名参加 ↗' : '登录后报名 ↗'}</button>`}</div>`);
}
function renderBookings() {
  $('#bookings').innerHTML = bookings.map(b => `<article class="booking"><div><span class="status ${b.status}">${statuses[b.status]}</span><h3>${escapeHtml(b.event.title)}</h3><p class="meta">◷ ${escapeHtml(displayDate(b.event.startsAt))} &nbsp; · &nbsp; ${escapeHtml(b.event.location)}</p>${b.event.cancellationReason ? `<p class="meta">活动取消原因：${escapeHtml(b.event.cancellationReason)}</p>` : ''}${b.checkedInAt ? `<p class="meta">签到于 ${escapeHtml(displayDate(b.checkedInAt))}</p>` : ''}</div><div class="booking-actions"><button class="btn btn-outline small" data-action="detail" data-id="${b.event.id}">活动详情</button>${b.status === 'REGISTERED' && b.event.lifecycle !== 'CANCELLED' ? `<button class="btn btn-dark small" data-action="checkin" data-id="${b.event.id}">输入签到码</button>${Date.now() < eventTime(b.event.startsAt) ? `<button class="btn btn-danger small" data-action="cancel-confirm" data-id="${b.event.id}">取消报名</button>` : ''}` : ''}</div></article>`).join('') || empty('你的下一段故事，还未开始', '去发现活动，选择一次想要参与的相遇。');
  $('#bookings').insertAdjacentHTML('beforeend', pager('mine',minePage,mineTotal,20));
}
async function loadAdmin() {
  const data = await api('/events?size=20&page=' + adminPage);
  $('#admin-events').innerHTML = data.items.map(e => `<div class="admin-row"><div><h3>${escapeHtml(e.title)} <span class="status">${lifecycleLabels[e.lifecycle] || ''}</span></h3><p>${escapeHtml(displayDate(e.startsAt))} &nbsp; · &nbsp; ${e.reservedCount} / ${e.capacity} 人</p></div><button class="btn btn-outline small" data-action="attendees" data-id="${e.id}">报名名单 / 签到码 ↗</button><button class="text-btn" data-action="event-history" data-id="${e.id}">操作流水</button>${!['CANCELLED','ENDED'].includes(e.lifecycle)?`<button class="btn btn-danger small" data-action="cancel-event" data-id="${e.id}">取消活动</button>`:''}</div>`).join('') || empty('发布你的第一场活动', '点击右上角，让一场新的相聚发生。');
  $('#admin-pagination').innerHTML = data.total > 20 ? `<button class="btn btn-outline small" data-action="admin-prev" ${adminPage === 0 ? 'disabled' : ''}>← 上一页</button><span>${adminPage + 1} / ${Math.ceil(data.total / 20)}</span><button class="btn btn-outline small" data-action="admin-next" ${(adminPage + 1) * 20 >= data.total ? 'disabled' : ''}>下一页 →</button>` : '';
}
function showCreate() {
  openModal(`<p class="modal-eyebrow">MAKE IT HAPPEN</p><h2>发布一场新的相聚</h2><form id="event-form" class="form-stack"><label>活动名称<input name="title" required maxlength="100" placeholder="一个让人想参加的名字"></label><div class="form-row"><label>活动类型<select name="category">${Object.entries(categories).map(([k, v]) => `<option value="${k}">${v[0]}</option>`).join('')}</select></label><label>人数上限<input name="capacity" type="number" min="1" max="10000" value="50" required></label></div><label>活动地点<input name="location" required maxlength="100" placeholder="教学楼、教室或场馆"></label><div class="form-row"><label>开始时间<input name="startsAt" type="datetime-local" required></label><label>结束时间<input name="endsAt" type="datetime-local" required></label></div><label>报名截止时间<input name="registrationDeadline" type="datetime-local" required></label><label>活动介绍<textarea name="description" required maxlength="2000" placeholder="告诉大家会发生什么，以及需要准备什么。"></textarea></label><p class="form-note">请填写北京时间。报名截止不能晚于活动开始。</p><p id="form-error" class="form-error" role="alert"></p><button type="submit" class="btn btn-dark">发布活动 ↗</button></form>`);
}
async function refreshAfterMutation() {
  await loadBookings(); if (currentView === 'mine') renderBookings();
  if (currentView === 'admin') await loadAdmin();
  await Promise.all([loadEvents(), loadStats()]);
}
async function finishMutation(message) {
  closeModal(); toast(message);
  try { await refreshAfterMutation(); }
  catch (e) { toast(message + '；列表刷新失败，请稍后刷新。' + (e.status === 401 ? '请重新登录。' : ''), true); }
}
let historyEvent = null, historyPage = 0;
async function showHistory() {
  const path = historyEvent ? `/admin/events/${historyEvent}/history` : '/registrations/history';
  const data = await api(path + '?page=' + historyPage + '&size=20');
  const labels = {REGISTERED:'报名',REREGISTERED:'重新报名',CANCELLED:'取消报名',ATTENDED:'签到',EVENT_PUBLISHED:'发布活动',EVENT_CANCELLED:'取消活动',EVENT_CANCELLED_BOOKING:'活动取消后撤销报名',CODE_ROTATED:'更新签到码'};
  openModal(`<h2>操作流水</h2><div class="table-wrap"><table><thead><tr><th>时间</th><th>动作</th><th>原因</th></tr></thead><tbody>${data.items.map(r=>`<tr><td>${escapeHtml(displayDate(r.occurredAt))}</td><td>${labels[r.action]||escapeHtml(r.action)}</td><td>${escapeHtml(r.reason)}</td></tr>`).join('')}</tbody></table></div>${pager('history',historyPage,data.total,20)}`);
}
async function showAttendees() {
  const [list, data] = await Promise.all([api(`/admin/events/${attendeeEvent}/attendees?page=${attendeePage}&size=20`),api(`/admin/events/${attendeeEvent}/checkin-code`)]);
  openModal(`<p class="modal-eyebrow">EVENT OPERATIONS</p><h2>报名名单与现场签到</h2><p>核对到场人员后再展示短时码。生成后两分钟内有效；更新后旧码立即失效。</p><div class="code-box">${escapeHtml(data.code || '未生成或已过期')}</div><p>有效至：${escapeHtml(displayDate(data.expiresAt))}</p><button class="btn btn-dark small" data-action="rotate-code" data-id="${attendeeEvent}">生成 / 更新两分钟签到码</button><p>共 ${list.total} 条报名记录（含已取消）</p><div class="table-wrap"><table><thead><tr><th>同学</th><th>状态</th><th>报名时间</th></tr></thead><tbody>${list.items.map(r=>`<tr><td>${escapeHtml(r.fullName)}<br><small>${escapeHtml(r.username)}</small></td><td>${statuses[r.status]}</td><td>${escapeHtml(displayDate(r.registeredAt))}</td></tr>`).join('')}</tbody></table></div>${pager('attendee',attendeePage,list.total,20)}`);
}

async function action(button) {
  const name = button.dataset.action, id = Number(button.dataset.id);
  if (name === 'login') showAuth();
  if (name === 'switch-auth') showAuth(authMode === 'login' ? 'register' : 'login');
  if (name === 'logout') {
    await api('/auth/logout', {method: 'POST'}); user = null; sessionRevision++; requestVersion++; csrf = ''; bookings = []; bookingStates = {}; minePage = 0;
    $('#bookings').innerHTML = ''; $('#admin-events').innerHTML = ''; $('#admin-pagination').innerHTML = ''; syncAccount();
    await showView('discover'); await finishMutation('已退出登录');
  }
  if (name === 'detail') await showDetail(id);
  if (name === 'go-mine') { closeModal(); await showView('mine'); }
  if (name === 'register') { if (!user) { showAuth(); return; } await api(`/events/${id}/registrations`, {method: 'POST'}); closeModal(); await finishMutation('报名成功，去「我的报名」查看'); }
  if (name === 'cancel-confirm') openModal(`<h2>取消这次报名</h2><form id="cancel-booking-form" data-id="${id}" class="form-stack"><label>取消原因<textarea name="reason" required maxlength="300" placeholder="例如课程冲突"></textarea></label><p id="form-error" class="form-error"></p><button type="submit" class="btn btn-danger">确认取消</button></form>`);
  if (name === 'cancel') { await api(`/events/${id}/registrations`, {method: 'DELETE'}); closeModal(); await finishMutation('报名已取消，名额已释放'); }
  if (name === 'checkin') openModal(`<p class="modal-eyebrow">YOU MADE IT</p><h2>记录这次相遇</h2><p>向现场组织者获取签到码。签到开放时间为开始前 30 分钟至活动结束。</p><form id="checkin-form" data-id="${id}" class="form-stack"><label>活动签到码<input name="code" required maxlength="16" autocomplete="off" placeholder="请输入签到码"></label><p id="form-error" class="form-error" role="alert"></p><button class="btn btn-dark" type="submit">完成签到 ↗</button></form>`);
  if (name === 'refresh-mine') { await loadBookings(); renderBookings(); toast('记录已更新'); }
  if (name === 'create-event') showCreate();
  if (name === 'attendees') { attendeeEvent = id; attendeePage = 0; await showAttendees(); }
  if (name === 'attendee-prev' || name === 'attendee-next') { attendeePage += name.endsWith('next') ? 1 : -1; await showAttendees(); }
  if (name === 'mine-prev' || name === 'mine-next') { minePage += name.endsWith('next') ? 1 : -1; await loadBookings(); renderBookings(); }
  if (name === 'rotate-code') {
    await api(`/admin/events/${id}/checkin-code`, {method:'POST'}); toast('新签到码已生成，旧码立即失效');
    try { await showAttendees(); } catch(e) { toast('新签到码已生成；显示刷新失败，请重新打开名单。',true); }
  }
  if (name === 'cancel-event') openModal(`<h2>取消活动</h2><p>未签到报名将取消并释放名额；已签到记录和操作流水保留。</p><form id="cancel-event-form" data-id="${id}" class="form-stack"><label>取消原因<textarea name="reason" required maxlength="300"></textarea></label><p id="form-error" class="form-error"></p><button class="btn btn-danger" type="submit">确认取消活动</button></form>`);
  if (name === 'verify-users') openModal('<h2>校园身份核验</h2><p>请先通过学校名册或现场证件核验，再输入准确用户名查询。不能只接受学生自报学号。</p><form id="user-search-form" class="form-stack"><label>用户名<input name="username" required maxlength="32"></label><p id="form-error" class="form-error"></p><button class="btn btn-dark" type="submit">查询学生</button></form>');
  if (name === 'verify-user') openModal(`<h2>记录校园身份核验</h2><p>请先核对学校名册或现场证件。每个学号仅可绑定一个账号。</p><form id="verify-form" data-id="${id}" class="form-stack"><label>已核验的学号<input name="campusId" required pattern="[a-zA-Z0-9-]{4,32}" maxlength="32"></label><p id="form-error" class="form-error"></p><button class="btn btn-dark" type="submit">确认已核验</button></form>`);
  if (name === 'event-history' || name === 'my-history') { historyEvent = name === 'event-history' ? id : null; historyPage = 0; await showHistory(); }
  if (name === 'history-prev' || name === 'history-next') { historyPage += name.endsWith('next') ? 1 : -1; await showHistory(); }
  if (name === 'prev' || name === 'next') { page += name === 'next' ? 1 : -1; await loadEvents(); $('#activity-list').scrollIntoView({behavior: 'smooth'}); }
  if (name === 'admin-prev' || name === 'admin-next') { adminPage += name === 'admin-next' ? 1 : -1; await loadAdmin(); }
}
document.addEventListener('click', async (event) => {
  const view = event.target.closest('[data-view]');
  const filter = event.target.closest('[data-category]');
  const button = event.target.closest('[data-action]');
  try {
    if (view) await showView(view.dataset.view);
    if (filter) { category = filter.dataset.category; page = 0; document.querySelectorAll('[data-category]').forEach(el => el.classList.toggle('active', el === filter)); await loadEvents(); }
    if (button && !button.disabled) { button.disabled = true; try { await action(button); } finally { button.disabled = false; } }
  } catch (e) { toast(e.message, true); }
});
document.addEventListener('submit', async (event) => {
  if (!['auth-form', 'event-form', 'checkin-form', 'cancel-event-form', 'user-search-form', 'verify-form', 'cancel-booking-form'].includes(event.target.id)) return;
  event.preventDefault(); const form = event.target, button = form.querySelector('[type="submit"]'); button.disabled = true;
  const data = Object.fromEntries(new FormData(form));
  try {
    if (form.id === 'auth-form') {
      if (authMode === 'register') { await api('/auth/register', {method: 'POST', body: data}); showAuth('login'); toast('注册成功，请登录'); return; }
      await api('/auth/login', {method: 'POST', body: new URLSearchParams(data)}); await refreshCsrf(); user = await api('/auth/me'); sessionRevision++; syncAccount(); closeModal(); const target = pendingView; pendingView = null; await finishMutation('欢迎回来，' + user.fullName); if (target && user) await showView(target);
    }
    if (form.id === 'event-form') { data.capacity = Number(data.capacity); await api('/admin/events', {method: 'POST', body: data}); closeModal(); await finishMutation('活动发布成功'); }
    if (form.id === 'checkin-form') { await api(`/events/${Number(form.dataset.id)}/check-in`, {method: 'POST', body: data}); closeModal(); await finishMutation('签到成功，愿这次相遇有所收获'); }
    if (form.id === 'cancel-event-form') { await api(`/admin/events/${Number(form.dataset.id)}/cancel`, {method:'POST', body:data}); await finishMutation('活动已取消'); }
    if (form.id === 'cancel-booking-form') { await api(`/events/${Number(form.dataset.id)}/registrations`, {method:'DELETE', body:data}); await finishMutation('报名已取消，名额已释放'); }
    if (form.id === 'verify-form') { await api(`/admin/users/${Number(form.dataset.id)}/verify`, {method:'POST', body:data}); await finishMutation('校园身份核验已记录'); }
    if (form.id === 'user-search-form') {
      const result = await api('/admin/users?username=' + encodeURIComponent(data.username));
      openModal(`<h2>学生查询结果</h2>${result.items.map(u=>`<div class="booking"><div><strong>${escapeHtml(u.fullName)}</strong><p>${escapeHtml(u.username)} · ${u.verified?'已核验':'未核验'}</p></div>${u.role==='STUDENT'&&!u.verified?`<button class="btn btn-dark small" data-action="verify-user" data-id="${u.id}">记录核验</button>`:''}</div>`).join('') || '<p>未找到该用户名。</p>'}`);
    }
  } catch (e) {
    if (e.status !== 401 || form.id === 'auth-form') {
      const error = form.querySelector('#form-error'); if (error && form.isConnected) error.textContent = e.message; else toast(e.message, true);
    }
  }
  finally { button.disabled = false; }
});
$('#search').addEventListener('input', (event) => { clearTimeout(searchTimer); const value = event.target.value; searchTimer = setTimeout(() => { search = value.trim(); page = 0; loadEvents().catch(e=>toast(e.message,true)); }, 280); });
$('.modal-close').addEventListener('click', closeModal);
$('#modal').addEventListener('click', (event) => { if (event.target === $('#modal')) { const rect = $('#modal').getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeModal(); } });
(async () => {
  try { await refreshCsrf(); try { user = await api('/auth/me'); } catch (e) { if (e.status !== 401) throw e; } syncAccount(); await loadBookings(); await Promise.all([loadEvents(), loadStats()]); }
  catch (e) { $('#events').innerHTML = empty('连接暂时不可用', '请确认服务已启动，然后刷新页面。'); toast(e.message, true); }
})();
