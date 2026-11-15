import {createApp} from 'vue';
import Settings from './Settings.vue';

const settings = document.getElementById('settings-app');
createApp(Settings).mount(settings);
settings.removeAttribute('aria-busy');
