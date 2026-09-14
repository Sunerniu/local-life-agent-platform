const {test}=require('node:test');
const assert=require('node:assert/strict');
const options=require('../nginx-1.18.0/html/hmdp/js/ops-agent.js');
function component(){const vm=structuredClone(options.data);Object.assign(vm,options.methods);vm.$set=(obj,key,value)=>obj[key]=value;return vm;}
test('unauthorized initial state is closed',()=>{const vm=component();assert.equal(vm.authorized,false);assert.equal(vm.context.canOperate,undefined);assert.deepEqual(vm.context.actions,[]);});
test('busy workflow ignores duplicate action',async()=>{const vm=component();vm.busy=true;let calls=0;await vm.run(async()=>calls++);assert.equal(calls,0);});
test('errors are visible and are not automatically retried',async()=>{const vm=component();let calls=0;await vm.run(async()=>{calls++;throw new Error('unknown result');});assert.equal(calls,1);assert.equal(vm.notice,'unknown result');assert.equal(vm.busy,false);});
test('approval checkbox resets even if request fails',async()=>{const vm=component();vm.confirmed.x=true;let task;vm.run=fn=>{task=fn();};vm.api=async()=>{throw new Error('network');};vm.decide({id:'x'},true);await assert.rejects(task);assert.equal(vm.confirmed.x,false);});
