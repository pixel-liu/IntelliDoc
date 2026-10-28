[CmdletBinding()]
param(
    [switch]$SkipInfrastructure,
    [switch]$NoFrontend,
    [switch]$NoBackend,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RuntimeDir = Join-Path $ProjectRoot '.runtime\launcher'
$PidDir = Join-Path $RuntimeDir 'pids'

function Show-Usage {
    @'
Usage: .\scripts\start-local.ps1 [options]

Starts missing local infrastructure, then the Spring Boot backend and Vite frontend.
Already-listening services are reused and never restarted.

Options:
  -SkipInfrastructure  Start only backend/frontend; require dependencies to be running.
  -NoBackend           Do not start the Spring Boot backend.
  -NoFrontend          Do not start the Vite frontend.
  -Help                Show this help.

Use .\scripts\stop-local.ps1 to stop the backend/frontend started by this script.
'@ | Write-Host
}

function Test-ListeningPort([int]$Port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1)
}

function Wait-ListeningPort([string]$Name, [int]$Port, [int]$TimeoutSeconds = 45) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-ListeningPort $Port) {
            Write-Host "  [OK] $Name is listening on $Port"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not start listening on port $Port within $TimeoutSeconds seconds."
}

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing environment file: $Path"
    }

    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $name = $matches[1]
            $value = $matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            Set-Item -Path ("Env:$name") -Value $value
        }
    }
}

function Save-ProcessRecord([string]$Role, [System.Diagnostics.Process]$Process) {
    New-Item -ItemType Directory -Path $PidDir -Force | Out-Null
    [ordered]@{
        role = $Role
        pid = $Process.Id
        startedAt = (Get-Date).ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PidDir "$Role.json") -Encoding UTF8
}

function Remove-ProcessRecord([string]$Role) {
    Remove-Item -LiteralPath (Join-Path $PidDir "$Role.json") -Force -ErrorAction SilentlyContinue
}

function Start-TrackedProcess {
    param(
        [Parameter(Mandatory)] [string]$Role,
        [Parameter(Mandatory)] [string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory)] [string]$WorkingDirectory
    )

    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -PassThru
    Save-ProcessRecord $Role $process
    Write-Host "  [STARTED] $Role (PID $($process.Id))"
    return $process
}

function Start-WindowsMySql {
    if (Test-ListeningPort 3306) { return }

    $service = @('MySQL80', 'mysql') |
        ForEach-Object { Get-Service -Name $_ -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if ($null -eq $service) {
        throw 'MySQL is not listening on 3306 and no MySQL80/mysql Windows service was found.'
    }
    Start-Service -Name $service.Name
    Wait-ListeningPort 'MySQL' 3306
}

function Start-Redis {
    if (Test-ListeningPort 6379) { return }

    $password = if ($env:SPRING_DATA_REDIS_PASSWORD) { $env:SPRING_DATA_REDIS_PASSWORD } else { $env:REDIS_PASSWORD }
    if ([string]::IsNullOrWhiteSpace($password)) {
        throw 'Redis is not listening on 6379 and SPRING_DATA_REDIS_PASSWORD/REDIS_PASSWORD is not configured.'
    }
    $distro = if ($env:REDIS_WSL_DISTRO) { $env:REDIS_WSL_DISTRO } else { 'Ubuntu-22.04' }
    Start-Process -FilePath 'wsl.exe' -ArgumentList @(
        '-d', $distro, '--exec', 'redis-server', '--port', '6379', '--bind', '0.0.0.0',
        '--protected-mode', 'yes', '--requirepass', $password, '--daemonize', 'yes'
    ) -WindowStyle Hidden -Wait
    Wait-ListeningPort 'Redis' 6379
}

function Start-Kafka {
    if (Test-ListeningPort 9092) { return }

    $kafkaHome = if ($env:KAFKA_HOME) { $env:KAFKA_HOME } else { 'D:\soft\kafka_2.13-4.3.1' }
    $launcher = Join-Path $kafkaHome 'bin\windows\kafka-server-start.bat'
    $config = Join-Path $ProjectRoot '.runtime\services\kafka-server.properties'
    if (-not (Test-Path -LiteralPath $launcher) -or -not (Test-Path -LiteralPath $config)) {
        throw 'Kafka launcher or local server configuration is missing. Set KAFKA_HOME or create .runtime/services/kafka-server.properties.'
    }
    Start-TrackedProcess 'kafka' $env:ComSpec @('/d', '/c', ('"{0}" "{1}"' -f $launcher, $config)) $kafkaHome | Out-Null
    try {
        Wait-ListeningPort 'Kafka' 9092 60
    }
    catch {
        Remove-ProcessRecord 'kafka'
        throw
    }
}

function Start-MinIo {
    if (Test-ListeningPort 9000) { return }

    $binary = if ($env:MINIO_BINARY) { $env:MINIO_BINARY } else { 'D:\soft\minIO\minio.exe' }
    $dataDir = if ($env:MINIO_DATA_DIR) { $env:MINIO_DATA_DIR } else { 'D:\minio-data' }
    if (-not (Test-Path -LiteralPath $binary)) {
        throw 'MinIO executable is missing. Set MINIO_BINARY.'
    }
    if ([string]::IsNullOrWhiteSpace($env:MINIO_ACCESS_KEY) -or [string]::IsNullOrWhiteSpace($env:MINIO_SECRET_KEY)) {
        throw 'MINIO_ACCESS_KEY and MINIO_SECRET_KEY must be set in .env.'
    }
    $env:MINIO_ROOT_USER = $env:MINIO_ACCESS_KEY
    $env:MINIO_ROOT_PASSWORD = $env:MINIO_SECRET_KEY
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
    Start-TrackedProcess 'minio' $binary @('server', $dataDir, '--address', ':9000', '--console-address', ':9001') (Split-Path -Parent $binary) | Out-Null
    try {
        Wait-ListeningPort 'MinIO' 9000
    }
    catch {
        Remove-ProcessRecord 'minio'
        throw
    }
}

function Start-Elasticsearch {
    if (Test-ListeningPort 9200) { return }

    $esHome = if ($env:ELASTICSEARCH_HOME) { $env:ELASTICSEARCH_HOME } else { 'D:\soft\elasticsearch-8.10.0-windows-x86_64\elasticsearch-8.10.0' }
    $launcher = Join-Path $esHome 'bin\elasticsearch.bat'
    if (-not (Test-Path -LiteralPath $launcher)) {
        throw 'Elasticsearch launcher is missing. Set ELASTICSEARCH_HOME.'
    }
    Start-TrackedProcess 'elasticsearch' $env:ComSpec @('/d', '/c', ('"{0}"' -f $launcher)) $esHome | Out-Null
    try {
        Wait-ListeningPort 'Elasticsearch' 9200 75
    }
    catch {
        Remove-ProcessRecord 'elasticsearch'
        throw
    }
}

function Start-Backend {
    if (Test-ListeningPort 8081) {
        Write-Host '  [REUSED] backend is already listening on 8081'
        return
    }
    $maven = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if ($null -eq $maven) { $maven = Get-Command 'mvn' -ErrorAction Stop }
    # Do not redirect Maven output into target/*.log. Application logs are bounded by logback-spring.xml.
    Start-TrackedProcess 'backend' $env:ComSpec @('/d', '/c', ('"{0}" spring-boot:run -Dspring-boot.run.profiles=dev' -f $maven.Source)) $ProjectRoot | Out-Null
    try {
        Wait-ListeningPort 'Backend' 8081 90
    }
    catch {
        Remove-ProcessRecord 'backend'
        throw
    }
}

function Start-Frontend {
    if (Test-ListeningPort 9527) {
        Write-Host '  [REUSED] frontend is already listening on 9527'
        return
    }
    $pnpm = Get-Command 'pnpm.cmd' -ErrorAction SilentlyContinue
    if ($null -eq $pnpm) { $pnpm = Get-Command 'pnpm' -ErrorAction Stop }
    $frontend = Join-Path $ProjectRoot 'frontend'
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) {
        throw 'Frontend dependencies are missing. Run "pnpm install" in frontend first.'
    }
    Start-TrackedProcess 'frontend' $env:ComSpec @('/d', '/c', ('"{0}" dev --host 127.0.0.1 --open false' -f $pnpm.Source)) $frontend | Out-Null
    try {
        Wait-ListeningPort 'Frontend' 9527 60
    }
    catch {
        Remove-ProcessRecord 'frontend'
        throw
    }
}

if ($Help) {
    Show-Usage
    exit 0
}

Import-DotEnv (Join-Path $ProjectRoot '.env')
Write-Host 'Starting IntelliDoc local environment...'

if (-not $SkipInfrastructure) {
    Start-WindowsMySql
    Start-Redis
    Start-Kafka
    Start-MinIo
    Start-Elasticsearch
}
else {
    foreach ($dependency in @(@('MySQL', 3306), @('Redis', 6379), @('Kafka', 9092), @('MinIO', 9000), @('Elasticsearch', 9200))) {
        if (-not (Test-ListeningPort $dependency[1])) {
            throw "$($dependency[0]) is not listening on port $($dependency[1]). Remove -SkipInfrastructure or start it manually."
        }
    }
}

if (-not $NoBackend) { Start-Backend }
if (-not $NoFrontend) { Start-Frontend }

Write-Host ''
Write-Host 'IntelliDoc is ready:'
if (-not $NoBackend) { Write-Host '  Backend:  http://localhost:8081' }
if (-not $NoFrontend) { Write-Host '  Frontend: http://localhost:9527' }
