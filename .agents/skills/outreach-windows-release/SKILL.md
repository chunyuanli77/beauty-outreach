---
name: outreach-windows-release
description: "为 Beauty Outreach 构建和检查 Windows x64 便携 ZIP。适用于跨电脑一键运行、准备本地交付，或复验无开发环境的启动。"
---

# 构建并验证 Windows 便携包

1. 从仓库根目录读取 [启动说明](../../../README.md) 和 [构建脚本](../../../scripts/build-windows-portable.ps1)。确认 JDK 21 x64、Maven、uv、独立 CPython 3.12 x64 可用。便携包内带运行时，不复制 .venv 或目标电脑的安装目录。
2. 把输出指定到仓库外的新目录，运行 README 的构建命令。仅当依赖已缓存时用 -Offline。保留生成的 SHA-256、包内运行时来源和许可。用实际 ZIP 内容确认缺少凭据、H2、日志、过程材料或已有用户资料。
3. 把 ZIP 解压到另一个含空格的目录，在 PATH 仅含 Windows 系统目录的进程里运行 Start-Workbench.cmd。核对两个本地服务健康、工作台首页、合成导入与规则评分。验证频控或任务改动时，在隔离库创建并执行一个授权的 Mock 任务，再核对审计。
4. 涉及 DeepSeek 启动时，用占位值验证本地配置状态，且不提交语义任务。用户授权外部调用后才使用他们配置的 Key。被占用的端口用临时端口，原工作台独立运行。
5. 给出 ZIP、校验值、双击入口、实际验证结果和目标平台。除非用户明确请求发布，交付本地文件即可。
