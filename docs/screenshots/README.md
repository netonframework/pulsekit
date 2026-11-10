# 管理后台截图

这些图片来自实际运行的 `neton-application-front-pulse` 页面，使用只读模拟 API 填充数据，并非设计稿。所有应用、设备、崩溃、队列状态与统计数字均为虚构示例，界面标注 `PulseKit · DEMO` 和“模拟数据 / Synthetic data”。

| 图片 | 展示内容 |
| --- | --- |
| [overview.png](overview.png) | 活跃设备、会话、启动与事件趋势 |
| [versions.png](versions.png) | 构建质量、无崩溃率及版本新增问题 |
| [crash-issues.png](crash-issues.png) | 问题聚合、影响设备及处理状态 |
| [pipeline.png](pipeline.png) | 在线设备、消费队列与死信示例 |

## 复现方法

1. 按主 README 将 Pulse 前端模块加入 Neton Application 前端宿主，安装依赖并生成模块注册表。
2. 在 PulseKit 仓库根目录启动模拟接口（Python 3 标准库，无额外依赖）：

   ```bash
   python3 tools/readme-demo-server.py
   ```

3. 在前端宿主的 `apps/shell` 目录启动页面：

   ```bash
   NETON_BACKEND_URL=http://127.0.0.1:3132 NEXT_PUBLIC_NETON_DEFAULT_LOCALE=zh-CN pnpm exec next dev -p 3131
   ```

4. 打开 `http://127.0.0.1:3131/pulse/overview?projectId=pulse_demo_ios&days=7`，切换到版本对比、崩溃问题与采集管道。语言选简体中文，视口设为 1600 × 1100 后截图。

模拟服务仅监听回环地址 `127.0.0.1:3132`，不连接数据库，不接收 SDK 数据，也不实现真实认证、数据写入或管理操作。仅覆盖上述四个页面所需的读取接口；其他菜单和操作不属于演示范围。不要把模拟服务用于部署或真实链路验收。结束后停止模拟服务与前端进程。

## 本次验证范围

截图日期为 2026-11-10，使用 Chromium、中文浅色界面。为避免开发模式的调试浮层，最终截图使用 Next.js 编译模式构建后启动的真实页面。已检查四个页面的数据加载和图片展示。

前端宿主的完整构建被已有支付模块 `channel-list.tsx` 中 `ResourceField` 不接受 `hint` 的类型错误阻断；本次未修改支付模块。截图使用的 `next build --experimental-build-mode compile` 成功不代表完整类型检查通过，也不代表真实 Pulse 服务端已经端到端验收。
