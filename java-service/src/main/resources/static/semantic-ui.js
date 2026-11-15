import {api, formatTime, node, show} from './workbench.js';

const byId = id => document.getElementById(id);
const verdicts = {STRONG:'高相关，有明确需求', INTEREST:'相关兴趣 · 待人工复核', EXCLUDE:'建议排除', UNCERTAIN:'证据不足 · 待人工复核'};
const states = {PENDING:'等待分析', RUNNING:'正在分析', FAILED:'分析失败', STALE:'资料已更新，需重新分析', NOT_RUN:'未分析'};
const reasons = {
  NOT_CONFIGURED:'Python 评分服务尚未启用 DeepSeek。', AUTH_FAILED:'DeepSeek Key 无效。更新本地启动配置后重试。',
  BALANCE_EXHAUSTED:'DeepSeek 余额不足。请检查账户额度。', RATE_LIMITED:'DeepSeek 请求过多，请稍后重试。',
  TIMEOUT:'分析超时，可重试。', DAILY_LIMIT:'已达到今日调用上限。', INVALID_RESPONSE:'模型结果未通过证据校验。',
  PROVIDER_UNAVAILABLE:'语义分析服务暂不可用。', INTERRUPTED:'任务未完成，可重试。', NOT_ALLOWED:'该资料未登记用于外部分析。',
  PROFILE_CHANGED:'资料或规则已更新。请重新分析。', FIXTURE_NOT_ALLOWED:'该资料未登记用于外部分析。',
  PROVIDER_NOT_CONFIGURED:'Python 评分服务尚未启用 DeepSeek。', INPUT_REJECTED:'简介未通过外发前检查。',
  ANALYSIS_ALREADY_ACTIVE:'所选线索中已有正在分析的项目，请稍后重试。'
};

export function initSemantic({renderLeads, reloadLeads, currentLead}) {
  const filter = byId('semantic-filter');
  const mode = byId('semantic-mode');
  const batch = byId('semantic-batch');
  const retry = byId('semantic-retry');
  const single = byId('semantic-single');
  let summaries = new Map();
  const selected = new Set();
  let config;
  let job;
  let timer;
  let submitting = false;
  let detailRequest = 0;
  let pending;
  const storage = 'outreach.semantic.job';
  const pendingStorage = 'outreach.semantic.pending';

  function remember(id) {
    try { sessionStorage.setItem(storage, String(id)); } catch { /* Current page can still follow the job. */ }
  }

  function rememberPending(value) {
    pending = value;
    try {
      if (value) sessionStorage.setItem(pendingStorage, JSON.stringify(value));
      else sessionStorage.removeItem(pendingStorage);
    } catch { /* Current page can still retry the same request. */ }
  }

  function canRun() { return config?.configured && !submitting && !job?.pending && !job?.running; }

  function updateControls() {
    mode.disabled = submitting || (!config?.configured && config?.mode !== 'SEMANTIC');
    batch.disabled = !canRun() || (!selected.size && !pending);
    batch.textContent = pending ? '确认上次提交' : `分析已选线索${selected.size ? `（${selected.size}/50）` : ''}`;
    retry.disabled = !canRun() || Boolean(pending);
    retry.hidden = !(job?.failed || job?.stale) || ['RUNNING', 'PENDING', 'QUEUED'].includes(job?.status);
    single.disabled = !canRun() || Boolean(pending) || !summaries.get(currentLead())?.semanticAllowed;
  }

  async function loadConfig() {
    try {
      config = await api('semantic/status');
      mode.hidden = !config.configured && config.mode !== 'SEMANTIC';
      mode.textContent = config.mode === 'SEMANTIC' ? '切换到规则模式' : '切换到语义模式';
      const currentMode = config.mode === 'SEMANTIC'
        ? (config.autoEnabled ? '语义模式 · 有效高相关结果可自动入池' : '语义模式 · 需人工确认入池')
        : '规则模式 · 按规则或人工决定入池';
      const provider = config.configured ? `今日已调用 ${config.usedToday}/${config.dailyLimit} 次。`
        : (reasons[config.blockedReason] ?? 'DeepSeek 尚未启用。');
      show('semantic-config', `${currentMode}。${provider}`);
    } catch {
      config = undefined;
      mode.hidden = true;
      show('semantic-config', '语义分析暂不可用。可继续使用本地工作台。');
    }
    updateControls();
  }

  async function loadSummaries() {
    try {
      const entries = await api('semantic/leads');
      summaries = new Map(entries.map(item => [item.leadId, item]));
      for (const id of selected) if (!summaries.get(id)?.semanticAllowed) selected.delete(id);
    } catch {
      summaries = new Map();
    }
    updateControls();
  }

  async function refresh() {
    await Promise.all([loadConfig(), loadSummaries()]);
    renderLeads();
  }

  function matches(lead) {
    if (!filter.value) return true;
    const result = summaries.get(lead.id);
    if (!result) return false;
    if (filter.value === 'ALLOWED') return result.semanticAllowed;
    if (filter.value === 'FAILED') return result.status === 'FAILED' || result.status === 'STALE';
    if (result.status !== 'SUCCEEDED') return false;
    if (filter.value === 'REVIEW') return !lead.manual_decision && !lead.selected
      && ['STRONG', 'INTEREST', 'UNCERTAIN'].includes(result.verdict);
    return result.verdict === filter.value;
  }

  function rowState(lead) {
    const result = summaries.get(lead.id);
    return {
      selectable: result?.semanticAllowed && config?.configured,
      checked: selected.has(lead.id),
      label: !result ? '—' : result.status === 'SUCCEEDED'
        ? (verdicts[result.verdict] || '待人工复核') : (states[result.status] || '未分析')
    };
  }

  function setSelection(leadId, checked) {
    if (checked && !selected.has(leadId) && selected.size >= 50) {
      show('semantic-error', '每批最多选 50 条。');
      return false;
    }
    if (checked) selected.add(leadId);
    else selected.delete(leadId);
    updateControls();
    return true;
  }

  async function showLead(id) {
    const request = ++detailRequest;
    const fields = byId('semantic-detail-fields');
    const evidence = byId('semantic-evidence');
    fields.hidden = true;
    fields.replaceChildren();
    evidence.replaceChildren();
    updateControls();
    show('semantic-detail-status', '正在读取判断…');
    try {
      const result = await api(`semantic/leads/${id}`);
      if (request !== detailRequest || id !== currentLead()) return;
      const allowed = summaries.get(id)?.semanticAllowed;
      if (result.status !== 'SUCCEEDED') {
        const detail = !allowed && result.status === 'NOT_RUN' ? '该资料未登记用于外部分析。'
          : (reasons[result.reason] ?? result.reason ?? states[result.status]);
        show('semantic-detail-status', detail || '未分析。');
        return;
      }
      show('semantic-detail-status', verdicts[result.verdict] ?? '待人工复核');
      for (const [label, value] of [['相关品类', result.product], ['兴趣或行动', result.signal], ['用途', result.purpose],
        ['判断理由', result.reason], ['模型、版本与时间', `${result.model || 'DeepSeek'} · ${result.promptVersion} · ${formatTime(result.updatedAt)}`]]) {
        if (value) fields.append(node('dt', label), node('dd', value));
      }
      fields.hidden = false;
      evidence.replaceChildren(...(result.evidence ?? []).map(quote => node('li', `「${quote}」`)));
    } catch (error) {
      if (request === detailRequest && id === currentLead()) show('semantic-detail-status', error.message);
    }
  }

  function showJob(value) {
    job = value;
    remember(value.id);
    const status = {PENDING:'等待中', RUNNING:'进行中', SUCCEEDED:'已完成', PARTIAL:'部分完成', FAILED:'已停止', STALE:'已过期'};
    const issues = new Map();
    for (const item of value.items ?? []) {
      if (['FAILED', 'STALE'].includes(item.status) && item.reason) issues.set(item.reason, (issues.get(item.reason) ?? 0) + 1);
    }
    const failures = [...issues].map(([reason, count]) => `${reasons[reason] ?? reason}（${count} 条）`).join(' ');
    const usage = Number.isFinite(value.inputTokens) && Number.isFinite(value.outputTokens)
      ? ` Token ${value.inputTokens} 入／${value.outputTokens} 出。估算 $${((value.inputTokens * 0.3 + value.outputTokens * 1.2) / 1e6).toFixed(4)}（按全量未缓存峰时计价）。` : '';
    show('semantic-progress', `批次 #${value.id} ${status[value.status] ?? value.status}。成功 ${value.succeeded} / ${value.total}，失败 ${value.failed}，过期 ${value.stale}。${usage}`);
    if (failures) show('semantic-error', failures);
    const activeItem = value.items?.find(item => item.leadId === currentLead());
    if (activeItem && activeItem.status !== 'SUCCEEDED') {
      show('semantic-detail-status', (activeItem.reason && reasons[activeItem.reason]) ?? states[activeItem.status] ?? '正在分析…');
    }
    updateControls();
  }

  async function follow(id) {
    clearTimeout(timer);
    try {
      const next = await api(`semantic/jobs/${id}`);
      if (job && job.id !== id) return;
      show('semantic-error');
      showJob(next);
      if (next.pending || next.running) {
        timer = setTimeout(() => follow(id), 1250);
        return;
      }
      await Promise.all([reloadLeads(), loadConfig()]);
      if (currentLead()) void showLead(currentLead());
    } catch (error) {
      show('semantic-error', `${error.message} 可点击「刷新分析状态」接着查看。`);
    }
  }

  async function submit(input) {
    if (!canRun()) return;
    if (!pending) rememberPending({...input, idempotencyKey: crypto.randomUUID()});
    submitting = true;
    clearTimeout(timer);
    updateControls();
    show('semantic-error');
    try {
      const next = await api('semantic/jobs', 'POST', pending);
      rememberPending(undefined);
      selected.clear();
      showJob(next);
      renderLeads();
      void follow(next.id);
    } catch (error) {
      if (error.status >= 400 && error.status < 500) rememberPending(undefined);
      show('semantic-error', `${reasons[error.message] ?? error.message}${pending ? ' 点击「确认上次提交」可沿用同一请求。' : ''}`);
      if (currentLead()) show('semantic-detail-status', reasons[error.message] ?? error.message);
    } finally {
      submitting = false;
      updateControls();
    }
  }

  mode.addEventListener('click', async () => {
    const nextMode = config?.mode === 'SEMANTIC' ? 'RULES' : 'SEMANTIC';
    const message = nextMode === 'SEMANTIC'
      ? '启用语义模式？待分析、失败或结果过期的线索需要人工确认后才能入池。'
      : '切回规则模式？规则评分将重新用于自动入池。';
    if (!confirm(message)) return;
    submitting = true;
    updateControls();
    show('semantic-error');
    try {
      await api('semantic/mode', 'PUT', {mode: nextMode});
      await Promise.all([reloadLeads(), loadConfig()]);
    } catch (error) {
      show('semantic-error', reasons[error.message] ?? error.message);
      await loadConfig();
    } finally {
      submitting = false;
      updateControls();
    }
  });
  batch.addEventListener('click', () => submit({leadIds: [...selected]}));
  retry.addEventListener('click', () => submit({retryJobId: job.id}));
  single.addEventListener('click', () => submit({leadIds: [currentLead()]}));
  filter.addEventListener('change', renderLeads);
  byId('semantic-refresh').addEventListener('click', () => {
    if (job) void follow(job.id);
    else void refresh();
    if (currentLead()) void showLead(currentLead());
  });
  try {
    const saved = JSON.parse(sessionStorage.getItem(pendingStorage));
    if (saved?.idempotencyKey && (saved.leadIds?.length || saved.retryJobId)) pending = saved;
    const last = Number(sessionStorage.getItem(storage));
    if (Number.isSafeInteger(last) && last > 0) void follow(last);
  } catch { /* Storage is optional. */ }

  return {rowState, setSelection, matches, loadSummaries, refresh, showLead};
}
