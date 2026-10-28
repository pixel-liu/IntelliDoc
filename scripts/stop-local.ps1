[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PidDir = Join-Path $ProjectRoot '.runtime\launcher\pids'

function Show-Usage {
    @'
Usage: .\scripts\stop-local.ps1 [options]

Stops every process launched and recorded by start-local.ps1, including its
Kafka, MinIO and Elasticsearch dependencies.

Options:
  -WhatIf               Show processes that would be stopped.
  -Help                 Show this help.
'@ | Write-Host
}

function Get-DescendantProcessIds([int]$ParentId) {
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $ParentId" -ErrorAction SilentlyContinue)
    $ids = @()
    foreach ($child in $children) {
        $ids += Get-DescendantProcessIds $child.ProcessId
        $ids += $child.ProcessId
    }
    return $ids
}

if ($Help) {
    Show-Usage
    exit 0
}

if (-not (Test-Path -LiteralPath $PidDir)) {
    Write-Host 'No launcher PID records found; nothing to stop.'
    exit 0
}

$allowedRoles = @('backend', 'frontend', 'kafka', 'minio', 'elasticsearch')

Get-ChildItem -LiteralPath $PidDir -Filter '*.json' -File | ForEach-Object {
    $record = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
    if ($record.role -notin $allowedRoles) { return }

    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Remove-Item -LiteralPath $_.FullName -Force
        return
    }

    $processIds = @(Get-DescendantProcessIds $record.pid) + $record.pid
    if ($PSCmdlet.ShouldProcess("$($record.role) process tree ($($processIds -join ', '))", 'Stop')) {
        $processIds | Sort-Object -Descending | ForEach-Object {
            Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $_.FullName -Force
        Write-Host "[STOPPED] $($record.role)"
    }
}
