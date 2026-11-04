'use strict';
const $ = (selector) => document.querySelector(selector);
const categories = {TECH: ['技术交流', 'TECH &\nIDEAS', '⌘'], CAREER: ['职业成长', 'GROW &\nCONNECT', '↗'], CULTURE: ['文化艺术', 'ART &\nSTORIES', '✳'], SPORT: ['运动健康', 'MOVE &\nENERGY', '◎']};
const statuses = {REGISTERED: '已报名', CANCELLED: '已取消', ATTENDED: '已签到'};
let csrf = '', user = null, bookings = [], allEvents = [], page = 0, adminPage = 0, category = '', search = '', currentView = 'discover', authMode = 'login';
let toastTimer, searchTimer, requestVersion = 0;
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const date = (value) => value ? new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false}) : '—';
// Server date-times are Asia/Shanghai wall time, regardless of the browser's location.
const eventTime = (value) => new Date(value + '+08:00').getTime();
const displayDate = (value) => value ? date(new Date(eventTime(value)).toISOString()) : '—';
function toast(message, error = false) { const el = $('#toast'); el.textContent = message; el.className = 'toast' + (error ? ' error' : ''); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.add('hidden'), 4200); }
async function refreshCsrf() { const res = await fetch('/api/auth/csrf'); if (!res.ok) throw new Error('无法获取安全令牌'); csrf = (await res.json()).token; }
async function api(path, options = {}) {
  const method = options.method || 'GET';
  const headers = {...options.headers};
  if (method !== 'GET') { if (!csrf) await refreshCsrf(); headers['X-XSRF-TOKEN'] = csrf; }
  let body = options.body;
  if (body && !(body instanceof URLSearchParams)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  const res = await fetch('/api' + path, {...options, headers, body});
  const data = await res.json().catch(() => ({}));
  if (!res.ok) { const error = new Error(data.message || '请求失败，请稍后重试'); error.status = res.status; throw error; }
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
async function loadBookings() { bookings = user ? await api('/registrations') : []; }
function card(event) {
  const [label, english, symbol] = categories[event.category] || categories.TECH;
  const booked = bookings.find(b => b.event.id === event.id && b.status !== 'CANCELLED');
  const expired = Date.now() >= eventTime(event.registrationDeadline);
  const full = event.reservedCount >= event.capacity;
  const state = booked ? statuses[booked.status] : expired ? '报名截止' : full ? '名额已满' : '查看活动 ↗';
  return `<article class="event-card"><div class="event-visual ${escapeHtml(event.category)}"><span class="visual-type">${english.replace('\n', '<br>')}</span><span class="visual-symbol">${symbol}</span><span class="visual-line"></span><span class="event-number">CAMPUS EXPERIENCE / ${String(event.id).padStart(3, '0')}</span></div><div class="event-body"><span class="category-tag">${label}</span><h3>${escapeHtml(event.title)}</h3><p class="meta"><span>◷</span>${escapeHtml(displayDate(event.startsAt))}</p><p class="meta"><span>⌖</span>${escapeHtml(event.location)}</p><div class="event-bottom"><div class="capacity"><b>${event.reservedCount}</b> / ${event.capacity} 人报名<div class="progress"><i style="width:${Math.min(100, event.reservedCount / event.capacity * 100)}%"></i></div></div><button class="text-btn ${expired || full ? 'closed' : ''}" data-action="detail" data-id="${event.id}">${state}</button></div></div></article>`;
}
async function loadEvents() {
  const version = ++requestVersion;
  $('#events').innerHTML = '<div class="loading">正在寻找有趣的活动…</div>';
  try {
    const data = await api('/events?' + new URLSearchParams({page, size: 9, category, search}));
    if (version !== requestVersion) return;
    allEvents = data.items;
    $('#result-count').textContent = `${data.total} 个活动`;
    $('#events').innerHTML = data.items.map(card).join('') || empty('还没有找到活动', '试试其他关键词或活动类型。');
    $('#pagination').innerHTML = data.total > 9 ? `<button class="btn btn-outline small" data-action="prev" ${page === 0 ? 'disabled' : ''}>← 上一页</button><span>${page + 1} / ${Math.ceil(data.total / 9)}</span><button class="btn btn-outline small" data-action="next" ${(page + 1) * 9 >= data.total ? 'disabled' : ''}>下一页 →</button>` : '';
  } catch (e) { if (version === requestVersion) $('#events').innerHTML = empty('活动加载失败', e.message); }
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
  openModal(`<p class="modal-eyebrow">WELCOME TO CAMPUSFLOW</p><h2>${register ? '开启你的校园新故事' : '很高兴，在这里遇见你'}</h2><form id="auth-form" class="form-stack">${register ? '<label>你的名字<input name="fullName" required maxlength="40" autocomplete="name" placeholder="同学怎么称呼？"></label>' : ''}<label>用户名<input name="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_]{3,32}" autocomplete="username" placeholder="字母、数字或下划线"></label><label>密码<input name="password" type="password" required minlength="8" maxlength="64" autocomplete="${register ? 'new-password' : 'current-password'}" placeholder="至少 8 位字符"></label><p id="form-error" class="form-error" role="alert"></p><button type="submit" class="btn btn-dark">${register ? '创建账号' : '登录'} ↗</button></form><p class="switch-auth">${register ? '已有账号？' : '还没有账号？'}<button data-action="switch-auth">${register ? '去登录' : '立即注册'}</button></p>`);
}
async function showDetail(id) {
  const event = await api('/events/' + id);
  const b = bookings.find(b => b.event.id === id && b.status !== 'CANCELLED');
  const disabled = Date.now() >= eventTime(event.registrationDeadline) || event.reservedCount >= event.capacity;
  openModal(`<p class="modal-eyebrow">${categories[event.category]?.[0] || '校园活动'} / CAMPUS EXPERIENCE</p><h2>${escapeHtml(event.title)}</h2><div class="detail-meta"><p>◷ ${escapeHtml(displayDate(event.startsAt))} — ${escapeHtml(displayDate(event.endsAt))}</p><p>⌖ ${escapeHtml(event.location)}</p><p>报名截止：${escapeHtml(displayDate(event.registrationDeadline))}</p><p>${event.reservedCount} / ${event.capacity} 人 · 时间均为北京时间</p></div><p class="detail-description">${escapeHtml(event.description)}</p><div class="detail-actions">${b ? `<span class="status ${b.status}">${statuses[b.status]}</span><button class="btn btn-outline" data-action="go-mine">查看我的报名 ↗</button>` : `<button class="btn btn-dark" data-action="register" data-id="${id}" ${disabled ? 'disabled' : ''}>${disabled ? '报名已截止或名额已满' : user ? '报名参加 ↗' : '登录后报名 ↗'}</button>`}</div>`);
}
function renderBookings() {
  $('#bookings').innerHTML = bookings.map(b => `<article class="booking"><div><span class="status ${b.status}">${statuses[b.status]}</span><h3>${escapeHtml(b.event.title)}</h3><p class="meta">◷ ${escapeHtml(displayDate(b.event.startsAt))} &nbsp; · &nbsp; ${escapeHtml(b.event.location)}</p>${b.checkedInAt ? `<p class="meta">签到于 ${escapeHtml(displayDate(b.checkedInAt))}</p>` : ''}</div><div class="booking-actions"><button class="btn btn-outline small" data-action="detail" data-id="${b.event.id}">活动详情</button>${b.status === 'REGISTERED' ? `<button class="btn btn-dark small" data-action="checkin" data-id="${b.event.id}">输入签到码</button>${Date.now() < eventTime(b.event.startsAt) ? `<button class="btn btn-danger small" data-action="cancel-confirm" data-id="${b.event.id}">取消报名</button>` : ''}` : ''}</div></article>`).join('') || empty('你的下一段故事，还未开始', '去发现活动，选择一次想要参与的相遇。');
}
async function loadAdmin() {
  const data = await api('/events?size=20&page=' + adminPage);
  $('#admin-events').innerHTML = data.items.map(e => `<div class="admin-row"><div><h3>${escapeHtml(e.title)}</h3><p>${escapeHtml(displayDate(e.startsAt))} &nbsp; · &nbsp; ${e.reservedCount} / ${e.capacity} 人</p></div><button class="btn btn-outline small" data-action="attendees" data-id="${e.id}">报名名单 / 签到码 ↗</button></div>`).join('') || empty('发布你的第一场活动', '点击右上角，让一场新的相聚发生。');
  $('#admin-pagination').innerHTML = data.total > 20 ? `<button class="btn btn-outline small" data-action="admin-prev" ${adminPage === 0 ? 'disabled' : ''}>← 上一页</button><span>${adminPage + 1} / ${Math.ceil(data.total / 20)}</span><button class="btn btn-outline small" data-action="admin-next" ${(adminPage + 1) * 20 >= data.total ? 'disabled' : ''}>下一页 →</button>` : '';
}
function showCreate() {
  openModal(`<p class="modal-eyebrow">MAKE IT HAPPEN</p><h2>发布一场新的相聚</h2><form id="event-form" class="form-stack"><label>活动名称<input name="title" required maxlength="100" placeholder="一个让人想参加的名字"></label><div class="form-row"><label>活动类型<select name="category">${Object.entries(categories).map(([k, v]) => `<option value="${k}">${v[0]}</option>`).join('')}</select></label><label>人数上限<input name="capacity" type="number" min="1" max="10000" value="50" required></label></div><label>活动地点<input name="location" required maxlength="100" placeholder="教学楼、教室或场馆"></label><div class="form-row"><label>开始时间<input name="startsAt" type="datetime-local" required></label><label>结束时间<input name="endsAt" type="datetime-local" required></label></div><label>报名截止时间<input name="registrationDeadline" type="datetime-local" required></label><label>活动介绍<textarea name="description" required maxlength="2000" placeholder="告诉大家会发生什么，以及需要准备什么。"></textarea></label><p class="form-note">请填写北京时间。报名截止不能晚于活动开始。</p><p id="form-error" class="form-error" role="alert"></p><button type="submit" class="btn btn-dark">发布活动 ↗</button></form>`);
}
async function refreshAfterMutation() { await Promise.all([loadBookings(), loadStats()]); await loadEvents(); if (currentView === 'mine') renderBookings(); if (currentView === 'admin') await loadAdmin(); }
async function action(button) {
  const name = button.dataset.action, id = Number(button.dataset.id);
  if (name === 'login') showAuth();
  if (name === 'switch-auth') showAuth(authMode === 'login' ? 'register' : 'login');
  if (name === 'logout') { await api('/auth/logout', {method: 'POST'}); user = null; bookings = []; await refreshCsrf(); syncAccount(); await showView('discover'); await loadEvents(); toast('已退出登录'); }
  if (name === 'detail') await showDetail(id);
  if (name === 'go-mine') { closeModal(); await showView('mine'); }
  if (name === 'register') { if (!user) { showAuth(); return; } await api(`/events/${id}/registrations`, {method: 'POST'}); closeModal(); await refreshAfterMutation(); toast('报名成功，去「我的报名」查看'); }
  if (name === 'cancel-confirm') openModal(`<p class="modal-eyebrow">YOUR REGISTRATION</p><h2>确认取消这次报名？</h2><p>取消后名额会立即释放，报名截止前可再次报名。</p><button class="btn btn-danger" data-action="cancel" data-id="${id}">确认取消</button>`);
  if (name === 'cancel') { await api(`/events/${id}/registrations`, {method: 'DELETE'}); closeModal(); await refreshAfterMutation(); toast('报名已取消，名额已释放'); }
  if (name === 'checkin') openModal(`<p class="modal-eyebrow">YOU MADE IT</p><h2>记录这次相遇</h2><p>向现场组织者获取签到码。签到开放时间为开始前 30 分钟至活动结束。</p><form id="checkin-form" data-id="${id}" class="form-stack"><label>活动签到码<input name="code" required maxlength="16" autocomplete="off" placeholder="请输入签到码"></label><p id="form-error" class="form-error" role="alert"></p><button class="btn btn-dark" type="submit">完成签到 ↗</button></form>`);
  if (name === 'refresh-mine') { await loadBookings(); renderBookings(); toast('记录已更新'); }
  if (name === 'create-event') showCreate();
  if (name === 'attendees') {
    const [list, data] = await Promise.all([api(`/admin/events/${id}/attendees`), api(`/admin/events/${id}/checkin-code`)]);
    openModal(`<p class="modal-eyebrow">EVENT OPERATIONS</p><h2>报名名单与现场签到</h2><p>将签到码告知到场同学，请勿提前公开。</p><div class="code-box">${escapeHtml(data.code)}</div><p>共 ${list.length} 条报名记录（含已取消）</p><div class="table-wrap"><table><thead><tr><th>同学</th><th>状态</th><th>报名时间</th></tr></thead><tbody>${list.map(r => `<tr><td>${escapeHtml(r.fullName)}<br><small>${escapeHtml(r.username)}</small></td><td>${statuses[r.status]}</td><td>${escapeHtml(displayDate(r.registeredAt))}</td></tr>`).join('')}</tbody></table></div>`);
  }
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
  if (!['auth-form', 'event-form', 'checkin-form'].includes(event.target.id)) return;
  event.preventDefault(); const form = event.target, button = form.querySelector('[type="submit"]'); button.disabled = true;
  const data = Object.fromEntries(new FormData(form));
  try {
    if (form.id === 'auth-form') {
      if (authMode === 'register') { await api('/auth/register', {method: 'POST', body: data}); showAuth('login'); toast('注册成功，请登录'); return; }
      await api('/auth/login', {method: 'POST', body: new URLSearchParams(data)}); await refreshCsrf(); user = await api('/auth/me'); syncAccount(); closeModal(); await refreshAfterMutation(); toast('欢迎回来，' + user.fullName);
    }
    if (form.id === 'event-form') { data.capacity = Number(data.capacity); await api('/admin/events', {method: 'POST', body: data}); closeModal(); await refreshAfterMutation(); toast('活动发布成功'); }
    if (form.id === 'checkin-form') { await api(`/events/${Number(form.dataset.id)}/check-in`, {method: 'POST', body: data}); closeModal(); await refreshAfterMutation(); toast('签到成功，愿这次相遇有所收获'); }
  } catch (e) { const error = form.querySelector('#form-error'); if (error) error.textContent = e.message; else toast(e.message, true); }
  finally { button.disabled = false; }
});
$('#search').addEventListener('input', (event) => { clearTimeout(searchTimer); const value = event.target.value; searchTimer = setTimeout(() => { search = value.trim(); page = 0; loadEvents(); }, 280); });
$('.modal-close').addEventListener('click', closeModal);
$('#modal').addEventListener('click', (event) => { if (event.target === $('#modal')) { const rect = $('#modal').getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeModal(); } });
(async () => {
  try { await refreshCsrf(); try { user = await api('/auth/me'); } catch (e) { if (e.status !== 401) throw e; } syncAccount(); await loadBookings(); await Promise.all([loadEvents(), loadStats()]); }
  catch (e) { $('#events').innerHTML = empty('连接暂时不可用', '请确认服务已启动，然后刷新页面。'); toast(e.message, true); }
})();
