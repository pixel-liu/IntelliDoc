param(
    [string]$OutputDirectory = "build",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$OutputRoot = Join-Path $RepositoryRoot $OutputDirectory
$ReleaseRoot = Join-Path $OutputRoot "intellidoc-release"
$ArchivePath = Join-Path $OutputRoot "intellidoc-release.zip"
$BackendJar = Join-Path $RepositoryRoot "target/IntelliDoc-0.0.1-SNAPSHOT.jar"
$FrontendDist = Join-Path $RepositoryRoot "frontend/dist"
$ReleaseMavenSettings = Join-Path $PSScriptRoot "maven-settings-central.xml"

Push-Location $RepositoryRoot
try {
    if (-not $SkipBuild) {
        & mvn -s $ReleaseMavenSettings clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Backend build failed." }
    }

    if (-not (Test-Path -LiteralPath $BackendJar)) { throw "Backend JAR is missing." }
    $jarEntries = & jar tf $BackendJar
    if ($LASTEXITCODE -ne 0 -or -not ($jarEntries -match '^BOOT-INF/$')) {
        throw "Backend JAR is not an executable Spring Boot package (BOOT-INF is missing)."
    }

    if (-not $SkipBuild) {
        Push-Location (Join-Path $RepositoryRoot "frontend")
        try {
            & pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw "Frontend dependency verification failed." }
            & pnpm build
            if ($LASTEXITCODE -ne 0) { throw "Frontend build failed." }
        }
        finally {
            Pop-Location
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $FrontendDist "index.html"))) {
        throw "Frontend production build is missing."
    }

    if (Test-Path -LiteralPath $ReleaseRoot) {
        $resolvedOutput = [System.IO.Path]::GetFullPath($OutputRoot)
        $resolvedRelease = [System.IO.Path]::GetFullPath($ReleaseRoot)
        if (-not $resolvedRelease.StartsWith($resolvedOutput + [System.IO.Path]::DirectorySeparatorChar)) {
            throw "Unsafe release path: $resolvedRelease"
        }
        Remove-Item -LiteralPath $resolvedRelease -Recurse -Force
    }
    if (Test-Path -LiteralPath $ArchivePath) {
        Remove-Item -LiteralPath $ArchivePath -Force
    }

    New-Item -ItemType Directory -Path (Join-Path $ReleaseRoot "backend") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $ReleaseRoot "frontend") -Force | Out-Null
    Copy-Item -LiteralPath $BackendJar -Destination (Join-Path $ReleaseRoot "backend/intellidoc.jar")
    Copy-Item -Path (Join-Path $FrontendDist "*") -Destination (Join-Path $ReleaseRoot "frontend") -Recurse
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot "deploy") -Destination $ReleaseRoot -Recurse
    Compress-Archive -Path (Join-Path $ReleaseRoot "*") -DestinationPath $ArchivePath -CompressionLevel Optimal
    Write-Host "Release created: $ArchivePath"
}
finally {
    Pop-Location
}
