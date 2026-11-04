const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chromium',headless:true});
 const ctx=await browser.newContext({viewport:{width:1440,height:1100}});const page=await ctx.newPage();const errors=[];
 page.on('pageerror',e=>errors.push(String(e)));
 const origin=process.env.APP_URL || 'http://127.0.0.1:8080';
 async function api(path,method='GET',data){
  const token=(await (await ctx.request.get(origin+'/api/auth/csrf')).json()).token;
  const r=await ctx.request.fetch(origin+'/api'+path,{method,data,headers:{'X-XSRF-TOKEN':token}});
  return {status:r.status(),data:await r.json()};
 }
 async function login(name,password){
  if(!await page.locator('#auth-form').isVisible())await page.getByRole('button',{name:/登录 \/ 注册/}).click();
  await page.locator('[name=username]').fill(name);await page.locator('[name=password]').fill(password);
  await page.locator('#auth-form [type=submit]').click();await page.waitForFunction(()=>!document.querySelector('dialog').open);
 }
 async function logout(){await page.getByRole('button',{name:'退出',exact:true}).click();await page.getByRole('button',{name:/登录 \/ 注册/}).waitFor();}
 await page.goto(origin,{waitUntil:'domcontentloaded'});await page.locator('.event-card').first().waitFor();
 await page.screenshot({path:'docs/screenshots/discover.png',fullPage:true});
 // Anonymous signup may not choose a verified identity or gain permissions.
 let r=await api('/auth/register','POST',{username:'newstudent',password:'Student123!',fullName:'新同学',verified:true});assert.equal(r.status,201);
 await login('newstudent','Student123!');
 const list=(await api('/events')).data;const future=list.items.find(e=>e.title.includes('从校园到大厂'));const ongoing=list.items.find(e=>e.title.includes('正在进行'));
 r=await api(`/events/${future.id}/registrations`,'POST');assert.equal(r.status,403);
 await logout();await login('admin','Admin123!');await page.locator('#admin-nav').click();
 await page.getByRole('button',{name:'校园身份核验',exact:true}).click();await page.locator('#user-search-form [name=username]').fill('newstudent');await page.locator('#user-search-form [type=submit]').click();
 await page.getByRole('button',{name:'记录核验',exact:true}).click();await page.locator('[name=campusId]').fill('202600099');await page.locator('#verify-form [type=submit]').click();await page.waitForFunction(()=>!document.querySelector('dialog').open);
 // Short code rotation: old code must stop working immediately.
 const first=(await api(`/admin/events/${ongoing.id}/checkin-code`,'POST')).data;
 const second=(await api(`/admin/events/${ongoing.id}/checkin-code`,'POST')).data;assert.notEqual(first.code,second.code);
 await logout();await login('student','Student123!');await page.locator('[data-view=mine]').click();await page.locator('.booking').first().waitFor();
 r=await api(`/events/${ongoing.id}/check-in`,'POST',{code:first.code});assert.equal(r.status,400);
 await page.locator('.booking').filter({hasText:'正在进行'}).getByRole('button',{name:'输入签到码'}).click();await page.locator('[name=code]').fill(second.code);await page.locator('#checkin-form [type=submit]').click();await page.waitForFunction(()=>document.querySelector('.status.ATTENDED'));
 // Commit succeeds but the read refresh fails: UI must retain a success statement.
 let fail=false;
 await page.route('**/api/stats',route=>fail?route.fulfill({status:503,contentType:'application/json',body:'{"message":"refresh unavailable"}'}):route.continue());
 await page.locator('[data-view=discover]').click();await page.locator('.event-card').filter({hasText:'从校园到大厂'}).getByRole('button').click();await page.getByRole('button',{name:'报名参加 ↗'}).waitFor();fail=true;await page.getByRole('button',{name:'报名参加 ↗'}).click();
 await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('报名成功')&&document.querySelector('#toast').textContent.includes('刷新失败'));
 fail=false;assert.equal((await api('/registrations')).data.total,2);
 await page.locator('[data-view=mine]').click();await page.locator('.booking').filter({hasText:'从校园到大厂'}).getByRole('button',{name:'取消报名',exact:true}).click();await page.locator('[name=reason]').fill('课程冲突');await page.locator('#cancel-booking-form [type=submit]').click();await page.waitForFunction(()=>document.querySelector('.status.CANCELLED'));
 await page.getByRole('button',{name:'我的操作流水',exact:true}).click();await page.getByText('课程冲突',{exact:true}).waitFor();await page.locator('.modal-close').click();
 // Actual server logout behind the UI simulates expiry. The stale authenticated page must clear.
 await api('/auth/logout','POST');await page.getByRole('button',{name:'刷新记录 ↻'}).click();await page.locator('#auth-form').waitFor();
 assert.equal(await page.locator('#admin-nav').isVisible(),false);assert.equal(await page.locator('#bookings .booking').count(),0);
 await login('student','Student123!');await page.locator('#mine-view').waitFor({state:'visible'});await page.locator('.booking').first().waitFor();
 await page.locator('#toast').waitFor({state:'hidden'});await page.screenshot({path:'docs/screenshots/my-registrations.png',fullPage:true});
 await logout();await login('newstudent','Student123!');r=await api(`/events/${future.id}/registrations`,'POST');assert.equal(r.status,201);
 await logout();await login('admin','Admin123!');await page.locator('#admin-nav').click();
 await page.locator('.admin-row').filter({hasText:'从校园到大厂'}).getByRole('button',{name:'取消活动',exact:true}).click();await page.locator('[name=reason]').fill('场地维护');await page.locator('#cancel-event-form [type=submit]').click();await page.waitForFunction(()=>document.querySelector('#admin-events').textContent.includes('活动已取消'));
 r=await api(`/admin/events/${future.id}/attendees`);assert.equal(r.data.total,2);assert.ok(r.data.items.every(x=>x.status==='CANCELLED'));
 await page.locator('.admin-row').filter({hasText:'正在进行'}).getByRole('button',{name:'报名名单 / 签到码 ↗'}).click();
 await page.getByRole('button',{name:'生成 / 更新两分钟签到码'}).click();await page.waitForFunction(()=>/^[A-Z0-9]{8}$/.test(document.querySelector('.code-box')?.textContent));await page.locator('.modal-close').click();
 await page.locator('#toast').waitFor({state:'hidden'});await page.screenshot({path:'docs/screenshots/admin.png',fullPage:true});
 const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true});const mp=await mobile.newPage();await mp.goto(origin,{waitUntil:'domcontentloaded'});await mp.locator('.event-card').first().waitFor();await mp.screenshot({path:'docs/screenshots/mobile.png',fullPage:true});
 for(const width of [360,390,768,1024]){await mp.setViewportSize({width,height:900});assert.ok(await mp.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));}
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS: identity review, two-minute code rotation, registration, cancellation reason and history, event cancellation, 401 expiry/relogin, commit-success/read-failure messaging, responsive layouts.');
})().catch(e=>{console.error(e);process.exit(1)});
