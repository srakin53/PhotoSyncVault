param(
    [Parameter(Mandatory = $true)]
    [string]$ManifestUrl,
    [switch]$BuildApk
)

$ErrorActionPreference = "Stop"
$AssetsDir = Join-Path $PSScriptRoot "app\src\main\assets\photos"
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null

Write-Host "Fetching manifest from $ManifestUrl..."
$raw = Invoke-RestMethod -Uri $ManifestUrl
$items = if ($raw -is [System.Array]) { $raw } elseif ($raw.images) { $raw.images } else { @() }

$baseUri = [System.Uri]$ManifestUrl
$count = 0
foreach ($item in $items) {
    $imgUrl = if ($item -is [string]) { $item } elseif ($item.url) { $item.url } else { $item.src }
    if (-not $imgUrl) { continue }
    $resolvedUri = [System.Uri]::new($baseUri, $imgUrl)
    $name = if ($item.name) { $item.name } else { [System.IO.Path]::GetFileName($resolvedUri.LocalPath) }
    if (-not $name) { $name = "synced_$($count + 1).jpg" }
    $dest = Join-Path $AssetsDir $name
    if (-not (Test-Path $dest)) {
        Write-Host "Downloading $name..."
        Invoke-WebRequest -Uri $resolvedUri.AbsoluteUri -OutFile $dest
        $count++
    } else {
        Write-Host "Skipping existing $name"
    }
}

Write-Host "Synced $count new images into $AssetsDir"
if ($BuildApk) {
    Write-Host "Building debug APK..."
    & (Join-Path $PSScriptRoot "gradlew.bat") assembleDebug
}
