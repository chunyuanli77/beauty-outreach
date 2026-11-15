# Vue integration

This directory contains the unmodified production browser ESM runtime and MIT license from [vue@3.5.45](https://www.npmjs.com/package/vue/v/3.5.45). Both files are extracted from the upstream [npm tarball](https://registry.npmjs.org/vue/-/vue-3.5.45.tgz):

- `package/dist/vue.runtime.esm-browser.prod.js` → `vue.runtime.esm-browser.prod.js`
- `package/LICENSE` → `vue-LICENSE.txt`

Runtime SHA-256: `6bf54d9c68491a70bc8a2ab3748d330ef8306ea8fcea9647a27a224c650e9c71`.

The app uses render functions and serves the runtime locally. Update the runtime, license, checksum, and the version in `scripts/portable-windows/THIRD-PARTY.txt` together when changing versions.
