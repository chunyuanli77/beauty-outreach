[CmdletBinding()]
param(
    [switch]$DeepSeek,
    [switch]$AutoEnabled,
    [switch]$NoBrowser,
    [ValidateRange(1024, 65535)][int]$JavaPort = 18120,
    [ValidateRange(1024, 65535)][int]$PythonPort = 18110
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$data = Join-Path $root 'data'
$java = Join-Path $root 'runtime/java/bin/java.exe'
$python = Join-Path $root 'runtime/python/python.exe'
$jar = Join-Path $root 'app/workbench.jar'
$pythonApp = Join-Path $root 'app/python'
$fixture = Join-Path $root 'app/semantic-fresh.json'

function Start-ServiceProcess($Executable, $WorkingDirectory, $CommandLine, $Environment) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.WorkingDirectory = $WorkingDirectory
    $start.Arguments = $CommandLine
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    foreach ($name in @('DEEPSEEK_API_KEY','OUTREACH_SEMANTIC_INTERNAL_TOKEN','OUTREACH_SEMANTIC_FIXTURE_PATH',
            'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_APPLICATION_JSON')) {
        $start.EnvironmentVariables.Remove($name)
    }
    foreach ($name in $Environment.Keys) { $start.EnvironmentVariables[$name] = $Environment[$name] }
    try { return [Diagnostics.Process]::Start($start) }
    finally { foreach ($name in $Environment.Keys) { $start.EnvironmentVariables.Remove($name) } }
}

function Get-LocalPort([int]$Preferred, [int]$Reserved = 0) {
    if ($Preferred -eq $Reserved) { $Preferred = 0 }
    foreach ($port in @($Preferred, 0)) {
        $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        try {
            $socket.Start()
            $chosen = $socket.LocalEndpoint.Port
            if ($chosen -ne $Reserved) { return $chosen }
        } catch [Net.Sockets.SocketException] {
            if ($port -eq 0) { throw }
        } finally { $socket.Stop() }
    }
    throw 'Could not obtain a local port. Try starting again.'
}

function Read-LocalJson($Url) {
    $request = [Net.HttpWebRequest][Net.WebRequest]::Create($Url)
    $request.Proxy = $null
    $request.Timeout = 1000
    $response = $request.GetResponse()
    try {
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return ConvertFrom-Json -InputObject $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    } finally { $response.Dispose() }
}

function Wait-Ready($Process, $Url) {
    for ($i = 0; $i -lt 90; $i++) {
        if ($Process.HasExited) { throw "Service exited with code $($Process.ExitCode): $Url" }
        try { if ((Read-LocalJson $Url).status -eq 'ok') { return } } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw "Service startup timed out: $Url"
}

$lock = $null
$javaProcess = $null
$pythonProcess = $null
try {
    foreach ($file in @($java,$python,$jar,$fixture)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing file. Extract the entire ZIP first: $file" }
    }
    try { [void][IO.Directory]::CreateDirectory($data) }
    catch { throw 'Cannot write to this location. Extract the folder to a directory you can write to.' }
    try { $lock = [IO.File]::Open((Join-Path $data 'workbench.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { throw 'This copy of the workbench is already running or its data directory is in use.' }

    $PythonPort = Get-LocalPort $PythonPort
    $JavaPort = Get-LocalPort $JavaPort $PythonPort
    $base = "http://127.0.0.1:$JavaPort"
    $score = "http://127.0.0.1:$PythonPort"
    $pythonEnv = @{}
    $javaEnv = @{}
    $key = $null
    $bstr = [IntPtr]::Zero
    try {
        if ($DeepSeek) {
            $token = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
            $value = $env:DEEPSEEK_API_KEY
            if ([string]::IsNullOrWhiteSpace($value)) {
                $key = Read-Host 'DeepSeek API Key (hidden)' -AsSecureString
                $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($key)
                $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
            }
            $value = $value.Trim()
            if ($value -cnotmatch '^[!-~]{12,512}$') { throw 'Enter a valid DeepSeek API Key.' }
            $pythonEnv['DEEPSEEK_API_KEY'] = $value
            $pythonEnv['OUTREACH_SEMANTIC_INTERNAL_TOKEN'] = $token
            $javaEnv['OUTREACH_SEMANTIC_INTERNAL_TOKEN'] = $token
            $javaEnv['OUTREACH_SEMANTIC_FIXTURE_PATH'] = $fixture
        }
        $pythonArgs = '-I -B -m uvicorn scoring_service.main:app --app-dir "{0}" --host 127.0.0.1 --port {1}' -f $pythonApp,$PythonPort
        $pythonProcess = Start-ServiceProcess $python $pythonApp $pythonArgs $pythonEnv
    } finally {
        $pythonEnv.Clear()
        $value = $null
        if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        if ($key) { $key.Dispose() }
    }
    Wait-Ready $pythonProcess "$score/health"
    $auto = ([bool]($DeepSeek -and $AutoEnabled)).ToString().ToLowerInvariant()
    $javaArgs = '-jar "{0}" --server.address=127.0.0.1 --server.port={1} --outreach.scoring-url={2} "--spring.datasource.url=jdbc:h2:file:./data/outreach;WRITE_DELAY=0" --outreach.semantic-auto-enabled={3}' -f $jar,$JavaPort,$score,$auto
    $javaProcess = Start-ServiceProcess $java $root $javaArgs $javaEnv
    Wait-Ready $javaProcess "$base/health"
    if ($DeepSeek) {
        if ((Read-LocalJson "$base/api/semantic/status").configured -ne $true) { throw 'DeepSeek local configuration did not load.' }
    } else {
        $status = Read-LocalJson "$base/api/semantic/status"
        if ($status.mode -eq 'SEMANTIC') {
            $request = [Net.HttpWebRequest][Net.WebRequest]::Create("$base/api/semantic/mode")
            $request.Proxy = $null
            $request.Timeout = 3000
            $request.Method = 'PUT'
            $request.ContentType = 'application/json'
            $body = [Text.Encoding]::UTF8.GetBytes('{"mode":"RULES"}')
            $request.ContentLength = $body.Length
            $stream = $request.GetRequestStream()
            try { $stream.Write($body, 0, $body.Length) } finally { $stream.Dispose() }
            $response = $request.GetResponse()
            $response.Dispose()
        }
    }
    Write-Host "Workbench: $base/"
    Write-Host "Scoring service: $score/"
    Write-Host 'Keep this window open. Press Ctrl+C here to stop both services.'
    if (-not $NoBrowser) {
        try { Start-Process "$base/" } catch { Write-Host 'Open the workbench URL in a browser.' }
    }
    while (-not $javaProcess.HasExited -and -not $pythonProcess.HasExited) { Start-Sleep -Milliseconds 500 }
    throw 'One of the workbench services stopped.'
} finally {
    foreach ($process in @($javaProcess,$pythonProcess)) {
        if ($process) {
            if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) }
            $process.Dispose()
        }
    }
    if ($lock) { $lock.Dispose() }
}
