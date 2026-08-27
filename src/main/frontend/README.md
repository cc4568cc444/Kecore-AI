# Vue 前端入口

这个目录是当前前端入口，基于 Vue 3、Pinia 和 JavaScript。旧版静态前端入口已移除，Spring Boot 根路径会跳转到 `/vue/`。

## 本地开发

先启动 Spring Boot：

```bash
.\mvnw.cmd spring-boot:run
```

再启动 Vue 开发服务器：

```bash
cd src/main/frontend
npm install
npm run dev
```

开发入口为 `http://localhost:5173/vue/`。接口通过 Vite proxy 转发到 `http://localhost:8088`。

## 构建入口

```bash
npm run build
```

构建产物输出到 `src/main/resources/static/vue`。Spring Boot 启动后，入口是 `/vue/`，`/` 和 `/index.html` 会跳转到该入口。

## 解耦策略

Vue 入口现在使用自己的 API 客户端、Pinia stores 和 Vue 组件实现交互逻辑，不再加载 `/app.js`、`/app-api.js`、`/app-config.js`、`/styles.css` 或 jQuery。主样式位于 `src/styles/app.css`，迁移补充样式位于 `src/styles/migration.css`。
