export function node(tag, text) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  return element;
}

export function show(id, value = '') {
  const element = document.getElementById(id);
  element.textContent = value;
  element.hidden = !value;
}

export async function api(path, method = 'GET', data, headers = {}) {
  const signal = AbortSignal.timeout(path === 'leads/score' ? 45000 : 12000);
  const options = {method, headers, signal};
  if (data !== undefined) {
    options.headers = {'Content-Type':'application/json', ...headers};
    options.body = JSON.stringify(data);
  }
  let response;
  try {
    response = await fetch('/api/' + path.replace(/^\//, ''), options);
  } catch {
    if (signal.aborted) throw new Error('请求超时。请刷新确认当前状态后重试。');
    throw new Error('无法连接本地业务服务，请检查服务是否已启动。');
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const reason = typeof body.message === 'string' && body.message !== 'No message available' ? body.message : '';
    const message = response.status === 503 && !path.startsWith('semantic/') ? '评分服务未响应，请启动 Python 服务后重试。'
      : reason ? reasonLabel(reason.replace(/^Row (\d+):\s*/i, '数据第 $1 条：')) : `请求失败（HTTP ${response.status}）。`;
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status !== 204) {
    try {
      return await response.json();
    } catch {
      if (signal.aborted) throw new Error('读取响应超时。请刷新确认当前状态后重试。');
      throw new Error('无法读取服务响应，请刷新确认当前状态。');
    }
  }
}

const time = new Intl.DateTimeFormat('zh-CN', {
  timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit',
  hour:'2-digit', minute:'2-digit', second:'2-digit', fractionalSecondDigits:3, hourCycle:'h23'
});

export function formatTime(value) {
  return value && Number.isFinite(Date.parse(value)) ? time.format(new Date(value)) : '—';
}

const statuses = new Map(Object.entries({
  DRAFT:'草稿', RUNNING:'执行中', PAUSED:'已暂停', COMPLETED:'已完成',
  PENDING:'待派发', RETRY:'待重试', ACCEPTED:'模拟受理', DELIVERED:'模拟送达',
  FAILED:'失败', UNCERTAIN:'待核对', SKIPPED:'已拦截'
}));
const reasons = new Map(Object.entries({
  OK:'成功', AUTHORIZED:'已授权', QUEUED:'等待派发', NO_CONTACT:'无联系方式', NO_CONSENT:'未授权',
  UNSUBSCRIBED:'已退订', BLACKLISTED:'黑名单', CONTACT_CHANGED:'联系方式已变更',
  TRANSIENT_ERROR:'暂时失败', PERMANENT_ERROR:'永久失败', UNKNOWN_RESULT:'结果不明',
  RETRY_EXHAUSTED:'已用完重试次数', MOCK_ACCEPTED:'模拟受理', MOCK_RECEIPT:'模拟送达回执',
  UNKNOWN_TEMPLATE_VARIABLE:'模板只支持 {{display_name}} 变量。', TEMPLATE_NAME_EXISTS:'模板名称已存在。',
  TEMPLATE_NOT_FOUND:'模板不存在，请刷新后重试。', LEAD_NOT_SELECTED:'有目标已不在目标池，请刷新后重新选择。',
  INVALID_TASK_TRANSITION:'任务状态已变化，请刷新任务。', TASK_NOT_FOUND:'找不到该任务。',
  IDEMPOTENCY_KEY_CONFLICT:'这个请求键已用于其他内容，请检查草稿。', INVALID_IDEMPOTENCY_KEY:'请求键格式不正确。',
  CONTACT_NOT_FOUND:'无联系方式', LEAD_NOT_FOUND:'找不到该线索。',
  AUTHORIZED_OR_SYNTHETIC_IMPORT:'导入完成', SCORING_COMPLETED:'评分完成', SCORING_CONFIG_UPDATED:'评分规则已更新',
  APPROVE:'人工入池', REJECT:'人工拒绝', AUTO:'按评分规则', DEDUPLICATED:'已去重'
}));

export function statusLabel(code) { return statuses.get(code) ?? code ?? '—'; }
export function reasonLabel(code) { return reasons.get(code) ?? statuses.get(code) ?? code ?? '—'; }
