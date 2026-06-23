function showTerminal() {
  dom.loginView.classList.add("hidden");
  dom.terminalView.classList.remove("hidden");
  setMessage(dom.loginMessage, "");
  refreshExternalData().then(() => renderAll());
}

function renderAll() {
  renderAccount();
  renderHoldings();
  renderOrders();
  renderTrades();
  renderAlerts();
  renderNotifications();
  renderProfile();
  dom.clockText.textContent = nowText();
  const account = currentAccount();
  dom.sessionAccount.textContent = account ? `${account.name} ${account.accountNo}` : "未登录";
}

function renderProfile() {
  const account = currentAccount();
  if (!account || !dom.profileForm) return;
  const profile = account.profile || {};
  dom.profileForm.name.value = profile.name || account.name || "";
  dom.profileForm.idNumber.value = profile.id_number || "";
  dom.profileForm.phone.value = profile.phone || "";
  dom.profileForm.address.value = profile.address || "";
  dom.profileForm.workUnit.value = profile.work_unit || "";
  dom.profileForm.occupation.value = profile.occupation || "";
  dom.profileForm.education.value = profile.education || "";
  dom.profileForm.phone.placeholder = profile.phone ? "" : "当前未填写";
  dom.profileForm.address.placeholder = profile.address ? "" : "当前未填写";
  dom.profileForm.workUnit.placeholder = profile.work_unit ? "" : "当前未填写";
  dom.profileForm.occupation.placeholder = profile.occupation ? "" : "当前未填写";
  dom.profileForm.education.placeholder = profile.education ? "" : "当前未填写";
  const profileFields = [
    ["姓名", profile.name || account.name || "未填写"],
    ["证件号码", profile.id_number || "未填写"],
    ["电话号码", profile.phone || "未填写"],
    ["地址", profile.address || "未填写"],
    ["工作单位", profile.work_unit || "未填写"],
    ["职业", profile.occupation || "未填写"],
    ["学历", profile.education || "未填写"],
  ];
  dom.profileCurrent.innerHTML = profileFields.map(([label, value]) => `<div><span>${label}</span><strong>${value}</strong></div>`).join("");
  dom.profileSyncStatus.textContent = "已从账户系统加载";
  updateProfileChangeHint();
}

function updateProfileChangeHint() {
  const account = currentAccount();
  if (!account || !dom.profileForm) return;
  const profile = account.profile || {};
  const fields = [
    ["电话号码", "phone", "phone"],
    ["地址", "address", "address"],
    ["工作单位", "work_unit", "workUnit"],
    ["职业", "occupation", "occupation"],
    ["学历", "education", "education"],
  ];
  const changed = fields.filter(([, apiKey, formKey]) => dom.profileForm[formKey].value.trim() !== (profile[apiKey] || ""));
  dom.profileChangeHint.textContent = changed.length ? `待保存修改：${changed.map(([label]) => label).join("、")}` : "未修改任何信息";
  dom.profileChangeHint.classList.toggle("has-changes", changed.length > 0);
}

function renderAccount() {
  const account = currentAccount();
  if (!account) return;
  const holdingValue = account.holdings.reduce((sum, item) => {
    const stock = state.stocks[item.stockCode];
    return stock ? sum + item.quantity * stock.latest : sum;
  }, 0);
  dom.availableCash.textContent = money(account.availableCash);
  dom.frozenCash.textContent = money(account.frozenCash);
  dom.totalAsset.textContent = money(account.availableCash + account.frozenCash + holdingValue);
  dom.accountStatus.textContent = account.status;
}

function renderHoldings() {
  const account = currentAccount();
  if (!account) return;
  dom.holdingRows.innerHTML = "";
  account.holdings.forEach((item) => {
    const stock = state.stocks[item.stockCode] || { name: item.stockCode, latest: 0 };
    const profit = (stock.latest - item.cost) * item.quantity;
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${item.stockCode}</td>
      <td>${stock.name}</td>
      <td>${item.quantity}</td>
      <td>${item.sellable}</td>
      <td>${money(item.cost)}</td>
      <td>${money(stock.latest)}</td>
      <td class="${profit >= 0 ? "gain" : "loss"}">${money(profit)}</td>
    `;
    dom.holdingRows.appendChild(row);
  });
  dom.holdingSummary.textContent = account.holdings.length ? `${account.holdings.length} 只股票` : "暂无持仓";
}

function renderMarket(stocks) {
  dom.marketResult.innerHTML = "";
  stocks.forEach((stock) => {
    const limits = getLimits(stock);
    const change = stock.latest - stock.prevClose;
    const changeRate = stock.prevClose ? (change / stock.prevClose) * 100 : 0;
    const card = document.createElement("article");
    card.className = "quote-card";
    card.innerHTML = `
      <div class="panel-header">
        <h3>${stock.name} ${stock.stockCode}</h3>
        <span>${stock.status}</span>
      </div>
      <div class="quote-grid">
        <div><span>最新价</span><strong>${money(stock.latest)}</strong></div>
        <div><span>涨跌幅</span><strong class="${change >= 0 ? "gain" : "loss"}">${changeRate.toFixed(2)}%</strong></div>
        <div><span>买一 / 卖一</span><strong>${price(stock.buyOne)} / ${price(stock.sellOne)}</strong></div>
        <div><span>最高 / 最低</span><strong>${price(stock.high)} / ${price(stock.low)}</strong></div>
        <div><span>涨停 / 跌停</span><strong>${price(limits.upper)} / ${price(limits.lower)}</strong></div>
      </div>
      <p class="hint">公告：${stock.announcement || "暂无公告"}</p>
    `;
    dom.marketResult.appendChild(card);
  });
}

function renderMarketList(stocks) {
  const sortedStocks = [...stocks].sort((left, right) => left.stockCode.localeCompare(right.stockCode));
  dom.marketSummary.textContent = `${sortedStocks.length} 只股票 · 每 5 秒自动刷新`;
  if (!sortedStocks.length) {
    dom.marketResult.innerHTML = `<div class="market-notice pending"><strong>暂无行情数据</strong><span>正在向中央交易系统请求全部股票行情...</span></div>`;
    return;
  }
  dom.marketResult.innerHTML = `<div class="table-wrap"><table class="market-table"><thead><tr><th>代码</th><th>名称</th><th>最新价</th><th>涨跌幅</th><th>买一 / 卖一</th><th>涨停 / 跌停</th><th>状态</th><th>操作</th></tr></thead><tbody></tbody></table></div>`;
  const rows = dom.marketResult.querySelector("tbody");
  sortedStocks.forEach((stock) => {
    const limits = getLimits(stock);
    const changeRate = stock.prevClose ? ((stock.latest - stock.prevClose) / stock.prevClose) * 100 : 0;
    const row = document.createElement("tr");
    row.innerHTML = `<td>${stock.stockCode}</td><td>${stock.name}</td><td>${money(stock.latest)}</td><td class="${changeRate >= 0 ? "gain" : "loss"}">${changeRate.toFixed(2)}%</td><td>${price(stock.buyOne)} / ${price(stock.sellOne)}</td><td>${price(limits.upper)} / ${price(limits.lower)}</td><td>${stock.status}</td><td class="market-actions"><button class="secondary-btn" data-trade-stock="${stock.stockCode}" data-trade-side="buy">买入</button><button class="ghost-btn" data-trade-stock="${stock.stockCode}" data-trade-side="sell">卖出</button></td>`;
    rows.appendChild(row);
  });
}

function renderSelectedTradeStock(stockCode) {
  const stock = state.stocks[stockCode];
  if (!stock) return dom.selectedTradeStock.classList.add("hidden");
  const limits = getLimits(stock);
  const changeRate = stock.prevClose ? ((stock.latest - stock.prevClose) / stock.prevClose) * 100 : 0;
  dom.selectedTradeStock.classList.remove("hidden");
  dom.selectedTradeStock.innerHTML = `<strong>已选择：${stock.name}（${stock.stockCode}）</strong><span>最新价 ${money(stock.latest)} · 涨跌幅 <b class="${changeRate >= 0 ? "gain" : "loss"}">${changeRate.toFixed(2)}%</b> · 买一/卖一 ${price(stock.buyOne)} / ${price(stock.sellOne)} · 涨停/跌停 ${price(limits.upper)} / ${price(limits.lower)} · ${stock.status}</span>`;
}

function renderOrders() {
  dom.orderRows.innerHTML = "";
  state.orders.forEach((order) => {
    const centralMode = API_CONFIG.centralBaseUrl || (API_CONFIG.clientBaseUrl && API_CONFIG.centralKafkaEnabled);
    const canCancel = ["未成交", "部分成交"].includes(order.status);
    const canSync = centralMode
      ? ["未成交", "部分成交", "撤单中"].includes(order.status)
      : order.status === "未成交";
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${order.id}</td>
      <td>${order.stockName} ${order.stockCode}</td>
      <td>${order.side === "buy" ? "买入" : "卖出"}</td>
      <td>${money(order.price)}</td>
      <td>${order.quantity}</td>
      <td>${order.tradedQuantity}</td>
      <td>${order.remainingQuantity}</td>
      <td>${order.status}</td>
      <td>
        <button class="secondary-btn" data-fill="${order.id}" ${canSync ? "" : "disabled"}>${centralMode ? "同步" : "成交"}</button>
        <button class="ghost-btn" data-cancel="${order.id}" ${canCancel ? "" : "disabled"}>撤销</button>
      </td>
    `;
    dom.orderRows.appendChild(row);
  });
  if (!state.orders.length) dom.orderRows.innerHTML = `<tr><td colspan="9">暂无委托记录</td></tr>`;
}

function renderTrades() {
  dom.tradeRows.innerHTML = "";
  state.trades.forEach((trade) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${trade.id}</td>
      <td>${trade.orderId}</td>
      <td>${trade.stockName} ${trade.stockCode}</td>
      <td>${money(trade.price)}</td>
      <td>${trade.quantity}</td>
      <td>${money(trade.amount)}</td>
      <td>${trade.time}</td>
    `;
    dom.tradeRows.appendChild(row);
  });
  if (!state.trades.length) dom.tradeRows.innerHTML = `<tr><td colspan="7">暂无成交结果</td></tr>`;
}

function renderAlerts() {
  dom.alertRows.innerHTML = "";
  state.alerts.forEach((alert) => {
    const item = document.createElement("article");
    item.className = "alert-item";
    item.innerHTML = `
      <div>
        <strong>${alert.stockName} ${alert.stockCode}</strong>
        <p class="hint">${alert.direction === "ABOVE" ? "高于或等于" : "低于或等于"} ${money(alert.price)} · ${alert.status} · ${alert.triggerTime || alert.createTime}</p>
      </div>
      <button class="ghost-btn" data-alert-delete="${alert.id}">删除</button>
    `;
    dom.alertRows.appendChild(item);
  });
  if (!state.alerts.length) dom.alertRows.innerHTML = `<p class="hint">暂无价格提醒</p>`;
  dom.alertSummary.textContent = `${state.alerts.length} 条规则`;
}

function renderNotifications() {
  if (!dom.notificationRows || !dom.notificationSummary) return;
  dom.notificationRows.innerHTML = "";
  state.notifications.forEach((notification) => {
    const item = document.createElement("article");
    item.className = `alert-item ${notification.readStatus === "UNREAD" ? "notification-unread" : ""}`.trim();
    item.innerHTML = `
      <div>
        <strong>${notification.readStatus === "UNREAD" ? "未读通知" : "已读通知"}</strong>
        <p class="hint">${notification.content} · ${notification.time || ""}</p>
      </div>
      <button class="ghost-btn" data-notification-read="${notification.id}" ${notification.readStatus === "READ" ? "disabled" : ""}>标为已读</button>
    `;
    dom.notificationRows.appendChild(item);
  });
  if (!state.notifications.length) dom.notificationRows.innerHTML = `<p class="hint">暂无通知</p>`;
  const unreadCount = state.notifications.filter((item) => item.readStatus === "UNREAD").length;
  dom.notificationSummary.textContent = `${unreadCount} 条未读 / ${state.notifications.length} 条通知`;
}

function setView(viewId) {
  if (!validateSession()) return;
  dom.navItems.forEach((item) => item.classList.toggle("active", item.dataset.view === viewId));
  dom.views.forEach((view) => view.classList.toggle("active-view", view.id === viewId));
  const active = [...dom.navItems].find((item) => item.dataset.view === viewId);
  dom.viewTitle.textContent = active ? active.textContent : "账户资产";
}
