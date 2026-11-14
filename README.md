# 美容仪线索筛选与 WhatsApp 模拟触达

本地工作台支持导入 Instagram 线索、规则评分、DeepSeek 语义筛选、人工复核、联系人授权、批量模拟派发和审计。Java 21 / Spring Boot 提供工作台与业务接口，Python 3.12 / FastAPI 负责评分，H2 在本机保存数据。

## 本地启动

安装 JDK 21、Maven 3.9+、Python 3.12 和 uv。以下两组命令分别从仓库根目录在两个终端打开。

```powershell
Set-Location python-service
uv sync --locked
uv run --locked python -m uvicorn scoring_service.main:app --host 127.0.0.1 --port 8001
```

```powershell
Set-Location java-service
mvn -B package
java -jar target/outreach-backend-0.1.0-SNAPSHOT.jar
```

打开 [工作台](http://127.0.0.1:8080/) 或 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。规则模式可直接在工作台导入 CSV 或 JSON，导入必填字段为 `username` 和 `source_url`。在入池候选详情配置 `MOCK-` 联系标识及授权后，可创建并运行模拟任务。默认数据库位于 `java-service/data/`。

## DeepSeek 语义筛选

完成上面的依赖安装和 Java 构建后，在仓库根目录单独启动一套语义工作台。脚本可通过隐藏提示读取 Key，并启动独立端口和本地数据库。用 `Ctrl+C` 停止该实例。

```powershell
.\scripts\start-semantic-workbench.ps1
```

打开 [语义工作台](http://127.0.0.1:18120/)。这套配置只分析 [运行时许可清单](scripts/semantic-fresh.json) 中匹配的合成简介与语言，下面的 PowerShell 命令可以生成一份可在页面上传的导入文件。

```powershell
$profiles = Get-Content -Raw -Encoding UTF8 .\scripts\semantic-fresh.json | ConvertFrom-Json
$i = 0
$records = foreach ($profile in $profiles) {
    $i++
    [pscustomobject]@{
        username = "mock_profile_$i"
        source_url = "https://example.invalid/profiles/$i"
        bio = $profile.bio
        language = $profile.language
    }
}
$records | ConvertTo-Json -AsArray -Depth 3 | Set-Content -Encoding UTF8 .\java-service\target\profiles.json
```

上传生成的 `profiles.json`，选择线索提交语义分析。在工作台切换到 DeepSeek 筛选模式可查看该模式下的入池结果。传入 `-AutoEnabled` 启用有效强候选的自动入池。脚本支持通过 `-JavaPort` 和 `-PythonPort` 设置其他端口。导入文件生成命令使用 PowerShell 7。
