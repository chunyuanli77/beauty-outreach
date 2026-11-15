import {createApp, h, onMounted, reactive, ref} from './vendor/vue.runtime.esm-browser.prod.js';
import {api} from './workbench.js';

const frequencyFields = [
  ['globalLimit', '窗口内最多尝试（次）', 1, 100],
  ['windowSeconds', '全局窗口（秒）', 1, 3600],
  ['cooldownSeconds', '同一联系人冷却（秒）', 0, 86400],
  ['retrySeconds', '首次重试间隔（秒）', 1, 3600]
];

function message(id, value, error = false) {
  return h('p', {id, class: error ? 'message error' : 'message', role: error ? 'alert' : 'status', hidden: !value}, value);
}

createApp({
  setup() {
    const templates = ref([]);
    const templateDraft = reactive({name: '', body: ''});
    const frequency = reactive(Object.fromEntries(frequencyFields.map(([name]) => [name, ''])));
    const templateList = reactive({loading: true, error: ''});
    const templateSave = reactive({pending: false, status: '', error: ''});
    const frequencyLoad = reactive({loading: true, ready: false});
    const frequencySave = reactive({pending: false, status: '', error: ''});

    async function loadTemplates() {
      templateList.loading = true;
      templateList.error = '';
      try {
        templates.value = await api('templates');
      } catch (error) {
        templateList.error = error.message;
      } finally {
        templateList.loading = false;
      }
    }

    function setFrequency(rules) {
      for (const [name] of frequencyFields) frequency[name] = String(rules[name]);
    }

    async function loadFrequency() {
      frequencyLoad.loading = true;
      frequencySave.error = '';
      try {
        setFrequency(await api('frequency'));
        frequencyLoad.ready = true;
      } catch (error) {
        frequencySave.error = error.message;
      } finally {
        frequencyLoad.loading = false;
      }
    }

    async function saveTemplate(event) {
      event.preventDefault();
      if (templateSave.pending) return;
      const name = templateDraft.name.trim();
      const body = templateDraft.body.trim();
      Object.assign(templateSave, {pending: true, status: '', error: ''});
      try {
        if (!name || !body) throw new Error('请输入模板名称和问候文案。');
        await api('templates', 'POST', {name, body});
        Object.assign(templateDraft, {name: '', body: ''});
        templateSave.status = '模板已保存。';
        await loadTemplates();
      } catch (error) {
        templateSave.error = error.message;
      } finally {
        templateSave.pending = false;
      }
    }

    async function saveFrequency(event) {
      event.preventDefault();
      if (!frequencyLoad.ready || frequencySave.pending) return;
      const rules = Object.fromEntries(frequencyFields.map(([name]) => [name, event.currentTarget.elements[name].valueAsNumber]));
      Object.assign(frequencySave, {pending: true, status: '', error: ''});
      try {
        setFrequency(await api('frequency', 'PUT', rules));
        frequencySave.status = '频控已保存。';
      } catch (error) {
        frequencySave.error = error.message;
      } finally {
        frequencySave.pending = false;
      }
    }

    onMounted(() => {
      void loadTemplates();
      void loadFrequency();
    });

    function formInput(tag, model, name, options) {
      return h(tag, {
        name, value: model[name], onInput: event => { model[name] = event.target.value; },
        required: true, ...options
      });
    }

    function submitButton(label, disabled) {
      return h('div', {class: 'actions'}, [h('button', {class: 'primary', type: 'submit', disabled}, label)]);
    }

    function templatePanel() {
      return h('section', {class: 'panel panel-body stack', 'aria-labelledby': 'templates-title'}, [
        h('h2', {id: 'templates-title'}, '问候模板'),
        h('div', {id: 'template-list', class: 'stack', 'aria-busy': templateList.loading}, [
          ...templates.value.map(template => h('div', {class: 'stack template-card', key: template.id}, [
            h('h3', template.name), h('div', {class: 'workbench-preview'}, template.body)
          ])),
          !templates.value.length && (templateList.loading ? h('p', {role: 'status'}, '正在加载模板…')
            : !templateList.error ? h('p', '暂时没有模板。') : null)
        ]),
        message('templates-error', templateList.error, true),
        h('form', {id: 'template-form', class: 'workbench-form', 'aria-busy': templateSave.pending, onSubmit: saveTemplate}, [
          h('label', ['模板名称', formInput('input', templateDraft, 'name', {
            id: 'template-name', maxlength: 100, disabled: templateSave.pending
          })]),
          h('label', ['问候文案', formInput('textarea', templateDraft, 'body', {
            id: 'template-body', rows: 4, maxlength: 1000, 'aria-describedby': 'template-help', disabled: templateSave.pending
          })]),
          h('p', {id: 'template-help', class: 'workbench-note'}, [
            h('code', '{{display_name}}'), ' 会替换成用户名。修改内容可另存为新模板。'
          ]),
          submitButton(templateSave.pending ? '正在保存…' : '新增模板', templateSave.pending)
        ]),
        message('template-status', templateSave.status),
        message('template-error', templateSave.error, true)
      ]);
    }

    function frequencyPanel() {
      const disabled = !frequencyLoad.ready || frequencySave.pending;
      return h('section', {class: 'panel panel-body stack', 'aria-labelledby': 'frequency-title'}, [
        h('h2', {id: 'frequency-title'}, '全局频控'),
        frequencyLoad.loading ? h('p', {role: 'status'}, '正在加载频控…') : null,
        h('form', {id: 'frequency-form', class: 'workbench-form', 'aria-busy': frequencySave.pending, onSubmit: saveFrequency}, [
          ...frequencyFields.map(([name, label, min, max]) => h('label', {key: name}, [label, formInput('input', frequency, name, {
            type: 'number', min, max, disabled
          })])),
          h('p', {class: 'workbench-note'}, '保存后适用于所有任务的后续发送尝试。暂时失败最多重试 2 次，仍受全局频控和联系人冷却限制。'),
          submitButton(frequencySave.pending ? '正在保存…' : '保存频控', disabled)
        ]),
        message('frequency-status', frequencySave.status),
        message('frequency-error', frequencySave.error, true)
      ]);
    }

    return () => [templatePanel(), frequencyPanel()];
  }
}).mount('#settings-app');

document.getElementById('settings-app').removeAttribute('aria-busy');
