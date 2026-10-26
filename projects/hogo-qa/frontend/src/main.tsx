import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, HashRouter } from 'react-router-dom';
import App from './App';
import './index.css';
import './print.css';
import { bootstrapSettings } from './ui/bootstrap/settingsBootstrap';

const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('未找到 #root 挂载节点，index.html 可能被破坏。');
}

/**
 * 应用启动接线（组合根）：在首次渲染前载入已保存的 AI 配置、
 * 判异准则开关与报表导出范围，并订阅变更自动落盘。
 * 详见 `@/ui/bootstrap/settingsBootstrap`。
 *
 * 放在入口而非 App 的 effect 中：恰好执行一次（不受 StrictMode 双调用影响），
 * 且保证首屏即读到已保存配置。
 */
bootstrapSettings();

/**
 * 选择路由实现（离线可用性的关键）。
 *
 * `file://` 协议下页面 origin 为 `null`，`BrowserRouter` 内部调用
 * `history.replaceState` 时浏览器会抛错：
 *
 *   Failed to execute 'replaceState' on 'History':
 *   A history state object with URL 'file:///E:/import' cannot be created
 *   in a document with origin 'null'
 *
 * 因此离线双击场景必须使用 `HashRouter`（基于 `location.hash`，不触碰
 * `replaceState`）。通过 HTTP 提供服务时仍用 `BrowserRouter`，以保留干净的
 * 路径式 URL。
 */
function selectRouter(): typeof BrowserRouter {
  const isFileProtocol = typeof window !== 'undefined' && window.location.protocol === 'file:';
  return (isFileProtocol ? HashRouter : BrowserRouter) as typeof BrowserRouter;
}

const Router = selectRouter();

/**
 * 路由基路径：由构建基路径（`vite.config.ts` 的 base）推导。
 *
 * - 独立部署 / 本地：`'/'`（或相对基 `'./'`）→ 不设前缀；
 * - 子路径部署（如 `/demo/hogo-qa/`）：BrowserRouter 需要 `basename`，
 *   否则刷新深链接时路由匹配不到，页面空白。
 * React Router 的 basename 不能带尾部斜杠（根路径除外）。
 */
const BASE_URL = import.meta.env.BASE_URL;
const BASENAME = BASE_URL.startsWith('/') ? BASE_URL.replace(/\/+$/, '') || '/' : '/';

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <Router basename={BASENAME}>
      <App />
    </Router>
  </React.StrictMode>,
);
