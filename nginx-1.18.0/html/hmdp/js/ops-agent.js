(function(){
  'use strict';
  const options={el:'#ops-app',data:{context:{actions:[]},authorized:false,busy:false,notice:'',question:'',answer:'',report:null,count:1,confirmed:{}},
    created(){this.refresh();},methods:{
      pretty(value){return JSON.stringify(value,null,2);},
      async api(path,body){
        const token=sessionStorage.getItem('token');
        if(!token)throw new Error('请先通过商家登录页登录，再由管理员单独授予运维权限。');
        const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),120000);
        try{
          const res=await fetch('/api/agent/ops'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',authorization:token},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal,cache:'no-store'});
          if(token!==sessionStorage.getItem('token'))throw new Error('登录身份已变化，请刷新页面。');
          if(res.status===401){sessionStorage.removeItem('token');this.authorized=false;this.context={actions:[]};this.report=null;throw new Error('登录已过期，请重新登录。');}
          if(res.status===403){this.authorized=false;this.context={actions:[]};this.report=null;}
          let payload;try{payload=await res.json();}catch(_){throw new Error('服务响应异常；若刚提交操作，请刷新核对，不要重复确认。');}
          if(!res.ok || !payload.success)throw new Error(payload.errorMsg || '操作未成功');
          return payload.data;
        }catch(error){if(error.name==='AbortError'||error instanceof TypeError)throw new Error('请求中断，结果不确定。请刷新审批核对，不要重复提交。');throw error;}finally{clearTimeout(timer);}
      },
      async refresh(){try{this.context=await this.api('/context');this.authorized=true;this.notice='';}catch(e){this.notice=e.message;}},
      async run(task){if(this.busy)return;this.busy=true;this.notice='';try{await task();}catch(e){this.notice=e.message;}finally{this.busy=false;}},
      diagnose(){this.run(async()=>{this.report=await this.api('/diagnose',{});await this.refresh();});},
      loadReport(id){this.run(async()=>{this.report=await this.api('/incidents/'+encodeURIComponent(id));});},
      chat(){this.run(async()=>{const reply=await this.api('/chat',{message:this.question,incidentId:this.report&&this.report.incidentId});if(reply.kind==='diagnosis')this.report=reply.data;this.answer=reply.message || (reply.kind==='proposal'?'申请已创建，尚未执行。请核对右侧审批卡。':'已采集实际证据，请查看诊断报告。');await this.refresh();});},
      propose(){this.run(async()=>{await this.api('/proposals',{incidentId:this.report.incidentId,count:this.count});await this.refresh();});},
      decide(action,approve){this.run(async()=>{const repaired=approve&&!!this.confirmed[action.id];this.$set(this.confirmed,action.id,false);await this.api('/actions/'+encodeURIComponent(action.id)+'/decision',{approve,repaired});await this.refresh();});},
      verify(action){this.run(async()=>{await this.api('/actions/'+encodeURIComponent(action.id)+'/verify',{});await this.refresh();});}
    }};
  if(typeof module!=='undefined'&&module.exports)module.exports=options;
  if(typeof Vue!=='undefined')new Vue(options);
}());
