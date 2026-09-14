(function () {
  'use strict';
  const statusLabels = {PENDING:'待确认', EXECUTING:'执行中', EXECUTED:'已完成', REJECTED:'已拒绝', FAILED:'执行失败', EXPIRED:'已过期', UNAVAILABLE:'已失效'};
  const toolLabels = {GetShop:'查询店铺资料', GetShopVouchers:'查询优惠券', UpdateShopHours:'申请修改营业时间'};
  function effectiveStatus(approval, now) {
    if (approval.status === 'PENDING' && Date.parse(approval.expiresAt) <= now) return 'EXPIRED';
    return approval.status;
  }
  function mergeApprovals(existing, incoming) {
    const map = new Map(existing.map(item => [item.id, item]));
    incoming.forEach(item => map.set(item.id, item));
    return Array.from(map.values()).sort((a, b) => Date.parse(b.expiresAt) - Date.parse(a.expiresAt)).slice(0, 50);
  }
  function resultMessage(approval) {
    if (typeof approval.result === 'string') return approval.result;
    return approval.result && approval.result.errorMsg || '未确认执行成功，请核对店铺状态后重新发起申请。';
  }

  const options = {
    el: '#merchant-app',
    data: function () {
      return {user:null, context:{configured:false, message:'', shops:[], approvals:[]}, selectedShopId:null,
        messages:[], approvals:[], draft:'', busy:false, loading:false, loggingIn:false, loggingOut:false,
        showLogin:false, loginError:'', credentials:{account:'', password:''}, notice:'', decisionBusy:null,
        now:Date.now(), serverOffset:0, authEpoch:0, messageId:0, timer:null, refreshTimer:null};
    },
    computed: {
      currentShop: function () { return this.context.shops.find(shop => shop.id === this.selectedShopId) || null; },
      canSend: function () { return !!this.user && this.context.configured && !!this.currentShop && !this.busy && !this.loggingOut; },
      pendingCount: function () { return this.approvals.filter(a => effectiveStatus(a,this.now) === 'PENDING').length; },
      visibleApprovals: function () {
        const items = this.approvals.filter(a => this.selectedShopId === null || a.shopId === this.selectedShopId);
        return items.slice().sort((a,b) => Number(effectiveStatus(b,this.now)==='PENDING') - Number(effectiveStatus(a,this.now)==='PENDING'));
      }
    },
    watch: {
      showLogin: function (open) {
        if (open) this.$nextTick(() => { if (this.$refs.accountInput) this.$refs.accountInput.focus(); });
      }
    },
    created: function () {
      this.restore();
      this.timer = setInterval(() => { this.now = Date.now() + this.serverOffset; }, 1000);
      this.refreshTimer = setInterval(() => { if (this.user && !this.loading && !this.busy && !this.decisionBusy) this.refresh(true); }, 15000);
    },
    beforeDestroy: function () { clearInterval(this.timer); clearInterval(this.refreshTimer); },
    methods: {
      async api(path, config) {
        config = config || {};
        const token = sessionStorage.getItem('token');
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), path.endsWith('/chat') ? 270000 : 20000);
        try {
          const response = await fetch('/api' + path, {
            method:config.method || 'GET', headers:{'Content-Type':'application/json', ...(token ? {authorization:token} : {})},
            body:config.body === undefined ? undefined : JSON.stringify(config.body), signal:controller.signal,
            credentials:'same-origin', cache:'no-store'
          });
          const date = response.headers.get('Date');
          if (date && Number.isFinite(Date.parse(date))) {
            this.serverOffset = Date.parse(date) - Date.now(); this.now = Date.now() + this.serverOffset;
          }
          if (response.status === 401) {
            if (token === sessionStorage.getItem('token')) this.resetSession();
            const error = new Error('登录已失效，请重新登录。'); error.status = 401; throw error;
          }
          let payload;
          try { payload = await response.json(); } catch (_) {
            const error = new Error('服务返回异常，请稍后刷新检查。'); error.status = response.status; throw error;
          }
          if (!response.ok || !payload || !payload.success) {
            const fallback = response.status === 403 ? '没有操作这家店铺的权限。' : '请求未成功，请稍后重试。';
            const error = new Error(payload && payload.errorMsg || fallback); error.status = response.status; throw error;
          }
          return payload.data;
        } catch (error) {
          if (error.name === 'AbortError') throw new Error('请求超时，结果尚不确定。请刷新审批区核对，不要重复提交修改。');
          if (error instanceof TypeError) throw new Error('暂时无法连接服务。请检查网络，并刷新审批区核对执行结果。');
          throw error;
        } finally { clearTimeout(timeout); }
      },
      resetSession() {
        sessionStorage.removeItem('token'); this.authEpoch++;
        this.user = null; this.context = {configured:false,message:'',shops:[],approvals:[]};
        this.selectedShopId = null; this.approvals = []; this.messages = []; this.draft = '';
      },
      async restore() {
        if (!sessionStorage.getItem('token')) return;
        try { this.user = await this.api('/user/me'); await this.refresh(); }
        catch (error) { this.notice = error.message; }
      },
      async refresh(silent) {
        if (!this.user || this.loading) return;
        const epoch = this.authEpoch;
        this.loading = true;
        try {
          const context = await this.api('/agent/merchant/context');
          if (epoch !== this.authEpoch) return;
          this.context = context;
          // 以服务端为准：撤权、重启或容量清理后不保留旧审批。
          this.approvals = mergeApprovals([], context.approvals || []);
          if (!context.shops.some(shop => shop.id === this.selectedShopId)) this.selectedShopId = context.shops.length ? context.shops[0].id : null;
          if (!silent) this.notice = '';
        } catch (error) { this.notice = error.message; }
        finally { this.loading = false; }
      },
      closeLogin() { if (!this.loggingIn) { this.showLogin = false; this.credentials.password = ''; } },
      async login() {
        if (this.loggingIn) return;
        this.loggingIn = true; this.loginError = '';
        try {
          const token = await this.api('/user/password-login', {method:'POST',body:{account:this.credentials.account.trim(),password:this.credentials.password}});
          this.resetSession(); sessionStorage.setItem('token', token);
          this.user = await this.api('/user/me'); this.showLogin = false; this.notice = '';
          await this.refresh();
        } catch (error) { this.loginError = error.message; }
        finally { this.credentials.password = ''; this.loggingIn = false; }
      },
      async logout() {
        if (this.busy || this.loggingOut) return;
        this.loggingOut = true;
        try { await this.api('/user/logout', {method:'POST'}); this.resetSession(); this.notice = ''; }
        catch (error) { this.notice = '退出未确认成功：' + error.message; }
        finally { this.loggingOut = false; }
      },
      suggest(text) {
        if (!this.user) { this.showLogin = true; return; }
        this.draft = text;
        const textarea = document.getElementById('agent-message'); if (textarea) textarea.focus();
      },
      enterSend(event) {
        if (event.isComposing || event.keyCode === 229) return;
        event.preventDefault(); this.send();
      },
      scrollToBottom() {
        this.$nextTick(() => { const el = this.$refs.conversation; if (el) el.scrollTop = el.scrollHeight; });
      },
      appendMessage(role, text, shopName, extra) {
        this.messages.push(Object.assign({id:++this.messageId,role,text,shopName},extra || {}));
        if (this.messages.length > 40) this.messages.splice(0,this.messages.length-40);
        this.scrollToBottom();
      },
      async send() {
        if (!this.user) { this.showLogin = true; return; }
        if (!this.canSend || !this.draft.trim() || this.draft.length > 4000) return;
        const input = this.draft.trim(), shop = this.currentShop, epoch = this.authEpoch;
        this.draft = ''; this.busy = true; this.notice = '';
        this.appendMessage('user',input,shop.name);
        try {
          const reply = await this.api('/agent/merchant/chat', {method:'POST',body:{message:input,shopId:shop.id}});
          if (epoch !== this.authEpoch) return;
          this.appendMessage('assistant',reply.message,shop.name,{steps:reply.steps || []});
          this.approvals = mergeApprovals(this.approvals, reply.approvals || []);
        } catch (error) {
          if (epoch === this.authEpoch) {
            this.appendMessage('assistant',error.message,shop.name,{error:true});
            if (!this.draft) this.draft = input;
          } else { this.notice = error.message; this.showLogin = true; }
        } finally { this.busy = false; this.scrollToBottom(); if (this.user) await this.refresh(true); }
      },
      async decide(approval, approve) {
        if (this.decisionBusy || effectiveStatus(approval,this.now) !== 'PENDING') return;
        this.decisionBusy = approval.id; this.notice = '';
        const epoch = this.authEpoch;
        try {
          const result = await this.api('/agent/merchant/approvals/' + encodeURIComponent(approval.id) + '/decision', {method:'POST',body:{approve}});
          if (epoch !== this.authEpoch) return;
          this.approvals = mergeApprovals(this.approvals,[result]);
          const text = result.status === 'EXECUTED' ? '营业时间修改成功，店铺资料已更新。' :
            result.status === 'REJECTED' ? '已拒绝这项申请，未修改店铺资料。' : resultMessage(result);
          this.appendMessage('assistant',text,approval.shopName,{error:result.status==='FAILED'});
        } catch (error) {
          this.notice = error.message;
          if (epoch === this.authEpoch && [403,404,410].includes(error.status)) {
            this.approvals = mergeApprovals(this.approvals,[Object.assign({},approval,{status:error.status===410 ? 'EXPIRED' : 'UNAVAILABLE'})]);
          }
          // 网络错误保留待核对状态；不自动重试确认。
        } finally { this.decisionBusy = null; if (this.user) await this.refresh(true); }
      },
      clearChat() { if (!this.busy) this.messages = []; },
      effectiveStatus(approval) { return effectiveStatus(approval,this.now); },
      approvalLabel(status) { return statusLabels[status] || status; },
      toolLabel(tool) { return toolLabels[tool] || '工具调用'; },
      stepLabel(status) { return {COMPLETED:'已查询',PENDING_APPROVAL:'等待审批',DENIED:'已拦截',FAILED:'未成功'}[status] || status; },
      remaining(approval) {
        const seconds = Math.max(0,Math.ceil((Date.parse(approval.expiresAt)-this.now)/1000));
        return Math.floor(seconds/60) + '分' + String(seconds%60).padStart(2,'0') + '秒';
      },
      resultMessage
    }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = {effectiveStatus,mergeApprovals,resultMessage,options};
  if (typeof Vue !== 'undefined') new Vue(options);
}());
