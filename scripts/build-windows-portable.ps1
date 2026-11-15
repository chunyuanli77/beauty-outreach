[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PythonRuntime,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$targetPlatform = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture
if ($env:OS -ne 'Windows_NT' -or $targetPlatform -ne 'X64') { throw 'Build this Windows x64 package on Windows x64.' }
$python = (Resolve-Path -LiteralPath $PythonRuntime).ProviderPath
if (-not $JavaHome) { throw 'Set JAVA_HOME to the Windows x64 JDK 21 directory.' }
$java = (Resolve-Path -LiteralPath $JavaHome).ProviderPath
foreach ($path in @((Join-Path $python 'python.exe'), (Join-Path $python 'LICENSE.txt'), (Join-Path $java 'bin/jlink.exe'), (Join-Path $java 'jmods/java.base.jmod'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing build input: $path" }
}
$pythonInfo = & (Join-Path $python 'python.exe') -I -B -c 'import platform,sys; print(".".join(map(str, sys.version_info[:3])),platform.machine()); raise SystemExit(not (sys.version_info[:2] == (3,12) and platform.machine() == "AMD64"))'
if ($LASTEXITCODE -ne 0) { throw 'The bundled Python must be standalone CPython 3.12 x64.' }
$release = ConvertFrom-StringData ((Get-Content -LiteralPath (Join-Path $java 'release')) -join [Environment]::NewLine)
if ($release.JAVA_VERSION -notmatch '^"21\.' -or $release.OS_ARCH -notin @('"amd64"','"x86_64"')) {
    throw 'The bundled Java must be JDK 21 x64.'
}
$mvn = (Get-Command mvn.cmd -CommandType Application).Source
$uv = (Get-Command uv -CommandType Application).Source
$out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if ($out.TrimEnd('\') -ieq $root.TrimEnd('\') -or $out.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Choose an output directory outside the source repository.'
}
[void][IO.Directory]::CreateDirectory($out)
$name = 'beauty-outreach-windows-x64'
$zip = Join-Path $out "$name.zip"
$shaFile = "$zip.sha256"
if ((Test-Path -LiteralPath $zip) -or (Test-Path -LiteralPath $shaFile)) { throw 'Use a new output directory to avoid overwriting an existing package.' }
$workspace = Join-Path $out ('.build-' + [guid]::NewGuid().ToString('N'))
$bundle = Join-Path $workspace $name
$javaBuild = Join-Path $workspace 'java-build'
$pythonBuild = Join-Path $workspace 'python-build'
foreach ($dir in @($bundle, $javaBuild, $pythonBuild, (Join-Path $bundle 'app/python'), (Join-Path $bundle 'runtime'))) {
    [void][IO.Directory]::CreateDirectory($dir)
}
[void][IO.Directory]::CreateDirectory((Join-Path $javaBuild 'src'))
Copy-Item -LiteralPath (Join-Path $root 'java-service/pom.xml') -Destination $javaBuild
Copy-Item -LiteralPath (Join-Path $root 'java-service/src/main') -Destination (Join-Path $javaBuild 'src') -Recurse
Copy-Item -LiteralPath (Join-Path $root 'python-service/pyproject.toml'), (Join-Path $root 'python-service/uv.lock') -Destination $pythonBuild
$oldJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $java
    Push-Location $javaBuild
    try {
        $mavenArgs = @('-B')
        if ($Offline) { $mavenArgs += '-o' }
        & $mvn @mavenArgs package
        if ($LASTEXITCODE -ne 0) { throw 'Java build failed.' }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $oldJavaHome }
Copy-Item -LiteralPath (Join-Path $javaBuild 'target/outreach-backend-0.1.0-SNAPSHOT.jar') -Destination (Join-Path $bundle 'app/workbench.jar')

Push-Location $pythonBuild
$priorProjectEnv = $env:UV_PROJECT_ENVIRONMENT
try {
    $env:UV_PROJECT_ENVIRONMENT = Join-Path $pythonBuild '.venv'
    $uvArgs = @('sync','--locked','--no-dev','--python',(Join-Path $python 'python.exe'))
    if ($Offline) { $uvArgs += '--offline' }
    & $uv @uvArgs
    if ($LASTEXITCODE -ne 0) { throw 'Locked Python dependency installation failed.' }
} finally {
    $env:UV_PROJECT_ENVIRONMENT = $priorProjectEnv
    Pop-Location
}

$javaRuntime = Join-Path $bundle 'runtime/java'
& (Join-Path $java 'bin/jlink.exe') --module-path (Join-Path $java 'jmods') --add-modules ALL-MODULE-PATH --strip-debug --no-man-pages --no-header-files --compress=2 --output $javaRuntime
if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }
if (Test-Path -LiteralPath (Join-Path $java 'NOTICE')) {
    Copy-Item -LiteralPath (Join-Path $java 'NOTICE') -Destination $javaRuntime
}
Copy-Item -LiteralPath (Join-Path $java 'release') -Destination (Join-Path $javaRuntime 'original-jdk-release.txt')
$pythonTarget = Join-Path $bundle 'runtime/python'
$sitePackages = Join-Path $pythonTarget 'Lib/site-packages'
$originalPackages = Join-Path $python 'Lib/site-packages'
$existing = @(Get-ChildItem -LiteralPath $originalPackages -Force -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -notin @('README.txt','pip') -and $_.Name -notmatch '^pip-[0-9].*\.dist-info$'
})
if ($existing.Count) { throw 'Use an unpacked standalone Python with no additional site-packages.' }
[void][IO.Directory]::CreateDirectory($sitePackages)
Get-ChildItem -LiteralPath $python -Force | Where-Object Name -notin @('Lib','Scripts','include','libs') |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $pythonTarget -Recurse }
Get-ChildItem -LiteralPath (Join-Path $python 'Lib') -Force | Where-Object Name -ne 'site-packages' |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $pythonTarget 'Lib') -Recurse }
Get-ChildItem -LiteralPath (Join-Path $pythonBuild '.venv/Lib/site-packages') -Force |
    Where-Object Name -notin @('_virtualenv.pth','_virtualenv.py') |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $sitePackages -Recurse }
# Bytecode caches from the development runtime are not needed in the portable bundle.
$runtimeRoot = (Resolve-Path -LiteralPath $pythonTarget).ProviderPath.TrimEnd('\') + '\'
foreach ($cache in Get-ChildItem -LiteralPath $pythonTarget -Directory -Filter '__pycache__' -Recurse -Force) {
    $cachePath = (Resolve-Path -LiteralPath $cache.FullName).ProviderPath
    if (-not $cachePath.StartsWith($runtimeRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Python cache outside bundle staging directory.' }
    Remove-Item -LiteralPath $cachePath -Recurse -Force
}
$bytecode = Get-ChildItem -LiteralPath $pythonTarget -File -Recurse -Force | Where-Object Extension -in @('.pyc','.pyo')
foreach ($file in $bytecode) { Remove-Item -LiteralPath $file.FullName -Force }

$app = Join-Path $bundle 'app'
$scorer = Join-Path $root 'python-service/scoring_service'
foreach ($nameOfFile in @('__init__.py','main.py','semantic.py')) {
    $packageDir = Join-Path $app 'python/scoring_service'
    [void][IO.Directory]::CreateDirectory($packageDir)
    Copy-Item -LiteralPath (Join-Path $scorer $nameOfFile) -Destination $packageDir
}
$fixturePath = Join-Path $root 'scripts/semantic-fresh.json'
Copy-Item -LiteralPath $fixturePath -Destination $app
$launchers = Join-Path $PSScriptRoot 'portable-windows'
foreach ($launcher in @('Start-Workbench.cmd','Start-DeepSeek.cmd','start-workbench.ps1','START-HERE.txt','THIRD-PARTY.txt')) {
    Copy-Item -LiteralPath (Join-Path $launchers $launcher) -Destination $bundle
}
$utf = [Text.UTF8Encoding]::new($false)
$profiles = @(Get-Content -LiteralPath $fixturePath -Encoding UTF8 -Raw | ConvertFrom-Json)
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
$importFile = ConvertTo-Json -InputObject @($records) -Depth 3
[IO.File]::WriteAllText((Join-Path $bundle 'sample-leads.json'), $importFile + [Environment]::NewLine, $utf)
$commit = & git -C $root rev-parse --verify HEAD
if ($LASTEXITCODE -ne 0) { $commit = 'unknown' }
elseif ((& git -C $root status --porcelain)) { $commit += ' (working tree had changes at build time)' }
$runtimeMeta = @("Source commit: $commit", "Java: $($release.JAVA_VERSION) / $($release.IMPLEMENTOR) / $($release.OS_ARCH)", "Python: $pythonInfo")
$pythonBuildStamp = Join-Path $python 'BUILD'
if (Test-Path -LiteralPath $pythonBuildStamp) {
    $runtimeMeta += 'Python build: ' + (Get-Content -LiteralPath $pythonBuildStamp -Raw).Trim()
}
[IO.File]::WriteAllLines((Join-Path $bundle 'BUILD-INFO.txt'), $runtimeMeta, $utf)
$pythonVersion = ($pythonInfo -split ' ')[0]
$notices = Join-Path $bundle 'THIRD-PARTY.txt'
$noticeText = [IO.File]::ReadAllText($notices)
$noticeText += "Python language source: https://github.com/python/cpython/tree/v$pythonVersion" + [Environment]::NewLine
if (Test-Path -LiteralPath $pythonBuildStamp) {
    $stamp = (Get-Content -LiteralPath $pythonBuildStamp -Raw).Trim()
    if ($stamp -match '^\d{8}$') {
        $noticeText += "Python standalone release: https://github.com/astral-sh/python-build-standalone/releases/tag/$stamp" + [Environment]::NewLine
    }
}
[IO.File]::WriteAllText($notices, $noticeText, $utf)
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($shaFile, "$hash  $name.zip" + [Environment]::NewLine, $utf)
Write-Output $zip
Write-Output "SHA-256: $hash"
