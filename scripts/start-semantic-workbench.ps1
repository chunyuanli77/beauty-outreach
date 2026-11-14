param(
    [ValidateRange(1024, 65535)][int]$JavaPort = 18120,
    [ValidateRange(1024, 65535)][int]$PythonPort = 18110,
    [string]$JavaJar,
    [string]$KeyFile,
    [switch]$AutoEnabled
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$pythonDir = Join-Path $root 'python-service'
$python = Join-Path $pythonDir '.venv/Scripts/python.exe'
$javaDir = Join-Path $root 'java-service'
$jar = if ($JavaJar) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($JavaJar) }
    else { Join-Path $javaDir 'target/outreach-backend-0.1.0-SNAPSHOT.jar' }
$fixturePath = Join-Path $PSScriptRoot "semantic-fresh.json"
if ($JavaPort -eq $PythonPort) { throw 'Choose different local ports for Java and Python.' }
foreach ($file in @($python, $jar, $fixturePath)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing prerequisite: $file" }
}
$java = (Get-Command java -CommandType Application).Source
foreach ($port in @($PythonPort, $JavaPort)) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() } catch { throw "Local port $port is occupied." } finally { $probe.Stop() }
}
$store = Join-Path $javaDir "target/semantic-fresh-$JavaPort/db"
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $store))
$token = ([guid]::NewGuid().ToString('N')) + ([guid]::NewGuid().ToString('N'))
$pythonProcess = $null
$javaProcess = $null

function Launch($File, $WorkingDirectory, $Arguments, $Environment) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $File
    $start.WorkingDirectory = $WorkingDirectory
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.EnvironmentVariables.Remove('DEEPSEEK_API_KEY')
    $start.EnvironmentVariables['OUTREACH_SEMANTIC_INTERNAL_TOKEN'] = $token
    try {
        foreach ($name in $Environment.Keys) { $start.EnvironmentVariables[$name] = $Environment[$name] }
        [Diagnostics.Process]::Start($start)
    } finally {
        foreach ($name in $Environment.Keys) { $start.EnvironmentVariables.Remove($name) }
        $start.EnvironmentVariables.Remove('OUTREACH_SEMANTIC_INTERNAL_TOKEN')
    }
}

function Wait-Health($Port, $Process) {
    $url = "http://127.0.0.1:$Port/health"
    for ($i = 0; $i -lt 90; $i++) {
        if ($Process.HasExited) { throw "Service on port $Port exited (code $($Process.ExitCode))." }
        try {
            if ((Read-LocalJson $url).status -eq 'ok') { return }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw "Health check timed out on port $Port."
}

function Read-LocalJson($Url) {
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        return Invoke-RestMethod -Uri $Url -NoProxy -TimeoutSec 2
    }
    $request = [Net.WebRequest]::CreateHttp($Url)
    $request.Proxy = $null
    $request.Timeout = 2000
    $response = $request.GetResponse()
    try {
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return ConvertFrom-Json -InputObject ($reader.ReadToEnd()) } finally { $reader.Dispose() }
    } finally {
        $response.Dispose()
    }
}

try {
    $key = $null
    $bstr = [IntPtr]::Zero
    try {
        if ($KeyFile) {
            $keyPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($KeyFile)
            if (-not (Test-Path -LiteralPath $keyPath -PathType Leaf)) { throw "API key file is missing: $keyPath" }
            try { $value = [IO.File]::ReadAllText($keyPath).Trim() }
            catch { throw "Cannot read the API key file as this Windows user: $keyPath" }
        } else {
            $key = Read-Host 'DeepSeek API Key (hidden)' -AsSecureString
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($key)
            $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Trim()
        }
        if ($value -cnotmatch '^[!-~]{12,512}$') {
            throw 'The API key is empty or invalid. Enter only the key on a single line, without quotes or an environment variable name.'
        }
        $pythonProcess = Launch $python $pythonDir "-B -m uvicorn scoring_service.main:app --host 127.0.0.1 --port $PythonPort" @{
            DEEPSEEK_API_KEY = $value
        }
    } finally {
        $value = $null
        if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        if ($key) { $key.Dispose() }
    }
    Wait-Health $PythonPort $pythonProcess
    try { $scorer = Read-LocalJson "http://127.0.0.1:$PythonPort/semantic/status" }
    catch { throw "Python is running on port $PythonPort, but its semantic status endpoint is unavailable. Check the Python source version." }
    if ($scorer.configured -ne $true) {
        throw 'Python started but did not accept the local DeepSeek key or internal token configuration.'
    }
    Write-Host "Python configuration loaded on port $PythonPort. Checking Java workbench..."
    $auto = $AutoEnabled.IsPresent.ToString().ToLowerInvariant()
    $db = $store.Replace('\', '/')
    $argsLine = '-jar "{0}" --server.port={1} --outreach.scoring-url=http://127.0.0.1:{2} "--spring.datasource.url=jdbc:h2:file:{3};WRITE_DELAY=0" --outreach.semantic-auto-enabled={4}' -f $jar,$JavaPort,$PythonPort,$db,$auto
    $javaProcess = Launch $java $javaDir $argsLine @{ OUTREACH_SEMANTIC_FIXTURE_PATH = $fixturePath }
    Wait-Health $JavaPort $javaProcess
    $semanticUrl = "http://127.0.0.1:$JavaPort/api/semantic/status"
    for ($i = 0; $i -lt 20; $i++) {
        $status = Read-LocalJson $semanticUrl
        if ($status.configured) { break }
        Start-Sleep -Seconds 1
    }
    if (!$status.configured) { throw "Python accepted its configuration, but Java on port $JavaPort could not use it. Check the Java package and local connection." }
    Write-Host "Semantic workbench: http://127.0.0.1:$JavaPort/"
    Write-Host 'Services are ready. Keep this window open. Ctrl+C stops both processes.'
    while (!$javaProcess.HasExited -and !$pythonProcess.HasExited) { Start-Sleep -Seconds 1 }
    throw 'One of the local services stopped.'
} finally {
    foreach ($process in @($javaProcess, $pythonProcess)) {
        if ($process) {
            if (!$process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) }
            $process.Dispose()
        }
    }
}
