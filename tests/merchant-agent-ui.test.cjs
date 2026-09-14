const {test} = require('node:test');
const assert = require('node:assert/strict');
const {effectiveStatus, mergeApprovals, resultMessage, options} = require('../nginx-1.18.0/html/hmdp/js/merchant-agent.js');

test('pending approvals expire, completed actions remain completed', () => {
  const approval = {status:'PENDING',expiresAt:'2026-09-07T00:10:00Z'};
  assert.equal(effectiveStatus(approval,Date.parse('2026-09-07T00:10:00Z')),'EXPIRED');
  assert.equal(effectiveStatus({...approval,status:'EXECUTED'},Date.parse('2026-09-07T01:00:00Z')),'EXECUTED');
});
test('server approval states replace old states without duplicate cards', () => {
  const approval = {id:'one',status:'PENDING',expiresAt:'2026-09-07T00:10:00Z'};
  const merged = mergeApprovals([approval],[{...approval,status:'REJECTED'}]);
  assert.equal(merged.length,1); assert.equal(merged[0].status,'REJECTED');
});
test('failure is never presented as success', () => {
  assert.equal(resultMessage({result:{success:false,errorMsg:'店铺已变化'}}),'店铺已变化');
  assert.match(resultMessage({result:null}),/未确认执行成功/);
});
test('unconfigured or unauthenticated users cannot send', () => {
  assert.equal(options.computed.canSend.call({user:null,context:{configured:true},currentShop:{id:1}}),false);
  assert.equal(options.computed.canSend.call({user:{id:1},context:{configured:false},currentShop:{id:1}}),false);
});
test('expired approval does not make an API call', async () => {
  let calls = 0;
  await options.methods.decide.call({decisionBusy:null,now:Date.parse('2026-09-07T01:00:00Z'),api:()=>{calls++;}},
    {id:'one',status:'PENDING',expiresAt:'2026-09-07T00:10:00Z'},true);
  assert.equal(calls,0);
});
test('failed decision is displayed and is not retried', async () => {
  let calls=0;
  const approval={id:'one',shopName:'测试店铺',status:'PENDING',expiresAt:'2026-09-07T00:10:00Z'};
  const ctx={decisionBusy:null,notice:'',authEpoch:1,now:Date.parse('2026-09-07T00:00:00Z'),approvals:[approval],user:{id:1},
    api:async()=>{calls++;return {...approval,status:'FAILED',result:{success:false,errorMsg:'状态已变化'}};},
    appendMessage:(role,text,name,extra)=>{assert.equal(text,'状态已变化');assert.equal(extra.error,true);},refresh:async()=>{}};
  await options.methods.decide.call(ctx,approval,true);
  assert.equal(calls,1);assert.equal(ctx.approvals[0].status,'FAILED');assert.equal(ctx.decisionBusy,null);
});
test('concurrent decision is blocked and network error is not retried', async () => {
  let calls=0;
  const ctx={decisionBusy:'other',api:()=>{calls++;}};
  await options.methods.decide.call(ctx,{id:'one'},true);assert.equal(calls,0);
  Object.assign(ctx,{decisionBusy:null,notice:'',authEpoch:1,user:null,now:0,api:async()=>{calls++;throw new Error('网络异常');}});
  await options.methods.decide.call(ctx,{id:'one',status:'PENDING',expiresAt:'2026-09-07T00:00:00Z'},true);
  assert.equal(calls,1);assert.equal(ctx.notice,'网络异常');
});
