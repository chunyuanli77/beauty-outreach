---
name: outreach-local-check
description: 验证 Outreach 本机工作台，检查 Java、Python、DeepSeek 配置和服务端口，或在独立空库中复跑导入去重与规则评分。用于启动排障、交接前验证和本地回归。
---

# Outreach 本地检查

## 已运行实例

先从启动输出或用户提供的地址确认 Java 与 Python 端口。只需检查运行状态时，使用以下只读请求。默认端口是 8080 和 8001，语义实例和便携版以各自启动输出为准。

```powershell
$base = 'http://127.0.0.1:8080'
$scorer = 'http://127.0.0.1:8001'
Invoke-RestMethod "$base/health" -TimeoutSec 5
Invoke-RestMethod "$scorer/health" -TimeoutSec 5
Invoke-RestMethod "$base/api/semantic/status" -TimeoutSec 5 |
    Select-Object configured, model, promptVersion, mode, autoEnabled
```

区分「工作台可访问」「Python 已就绪」「Java 可读语义配置」「实际模型已经返回结果」。`configured` 只证明本地配置就绪。规则模式允许 `configured=false`。默认状态检查不提交语义任务，也不打印密钥、内部令牌或数据库记录。

## 需要启动或验证数据写入时

当前版本的 [README](../../../README.md) 是启动入口。验证便携包时用它自带的启动器。验证源码时，另选两个空闲本机端口，以下命令分别在对应目录打开两个终端。请先确认目标 H2 路径尚未存在，写入检查只能使用本次验证的临时库。

```powershell
# python-service
uv sync --locked
uv run --locked python -m uvicorn scoring_service.main:app --host 127.0.0.1 --port 18281

# java-service
mvn -B package
java -jar target/outreach-backend-0.1.0-SNAPSHOT.jar --server.port=18280 --outreach.scoring-url=http://127.0.0.1:18281 '--spring.datasource.url=jdbc:h2:file:./target/smoke-18280/db;WRITE_DELAY=0'
```

- 可在页面导入一条合成线索并再次导入同一文件。接口复跑时，将 JSON 数组 POST 到 `/api/leads/import`。首轮应是 `created=1`，再导入应是 `created=0`、`matched=1` 且 `lead_ids` 一致。再 POST `/api/leads/score`，核对 `scored=1`。页面中也可核对分数与命中依据。
- 用户要核对 DeepSeek 时，用当前 [语义工作台启动脚本](../../../scripts/start-semantic-workbench.ps1)，在不同端口检查状态。只有需要实际模型结果时才提交获准且符合清单的合成简介。语义自动入池要求正确模式、开关开启和当前有效的强候选。
- 故障时先区分端口占用、运行时或构建失败、Java 到 Python 连接、语义配置或模型响应。如果端口已被其他进程使用，选择新的端口。只停止这次检查启动的进程。报告实际命令、成功或失败的断言，以及验证所用的模式和数据库。
