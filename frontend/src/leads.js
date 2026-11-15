import {createApp, h, shallowRef} from 'vue';
import LeadList from './LeadList.vue';
import LeadOverview from './LeadOverview.vue';

export function mountLeadViews({onOpenLead, onSelectionChange}) {
  const view = shallowRef({items: [], empty: '正在加载线索…'});

  createApp({render: () => h(LeadOverview, {metrics: view.value.metrics})}).mount('#lead-overview');
  createApp({render: () => h(LeadList, {
    items: view.value.items, empty: view.value.empty, onOpenLead, onSelectionChange
  })}).mount('#lead-rows');

  return view;
}
