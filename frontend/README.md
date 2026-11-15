# Vue 3 工作台前端

本目录是手写的 Vue 源代码。线索概况、线索列表和模板与频控由 Vue 单文件组件渲染：

| 页面 | Vue 组件 | 页面状态和 API |
| --- | --- | --- |
| 线索概况 | [LeadOverview.vue](src/LeadOverview.vue) | [leads.js](../java-service/src/main/resources/static/leads.js) 从当前线索清单计算三个数字 |
| 线索列表 | [LeadList.vue](src/LeadList.vue) | 同一页面维护搜索与分页，调用 [semantic-ui.js](../java-service/src/main/resources/static/semantic-ui.js) 管理分析选择与任务状态 |
| 模板与频控 | [Settings.vue](src/Settings.vue) | 在组件中读写 `/api/templates`、`/api/frequency` |

批量任务和审计继续使用 [Spring Boot 提供的静态页面](../java-service/src/main/resources/static/)。所有页面使用同源业务 API 和同一份 CSS。

修改 `.vue` 后，使用 Node 22.12+（建议 24 LTS）在当前目录重新生成浏览器资源：

```powershell
npm ci
npm run build
```

Vite 将两个入口编译到 [`static/vue/`](../java-service/src/main/resources/static/vue/)。提交源文件时一并提交生成文件；评审从源码执行 `mvn package` 时不必重装 npm 依赖。浏览器使用 [本地固定版本的 Vue 运行时](../java-service/src/main/resources/static/vendor/README.md)，运行路径无 CDN、开发服务器或外部字体。运行测试可以在 Java 工作台端口上查看实际页面。
