# Run through this project's pinned wk on worker only. No host SDK/keystore mounted.
param([switch]$ToolchainOnly)
$ErrorActionPreference='Stop'
if(-not $env:WK_RUN_TMP){throw 'Invoke through wk on worker; no local build'}
$image='wdtt-android-builder:toolchain-20261115'
& docker build -t $image -f (Join-Path $PSScriptRoot 'android-builder.Dockerfile') $PSScriptRoot
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
if($ToolchainOnly){exit 0}
$container='wdtt-android-'+[guid]::NewGuid().ToString('N')
$source=(Get-Location).Path
# Windows checkout line endings must not become Linux shell syntax.
$shellFiles=@((Join-Path $source 'gradlew'))
$shellFiles+=@(Get-ChildItem -Path (Join-Path $source 'scripts'),(Join-Path $source 'tools') -Filter '*.sh' -File -Recurse | Select-Object -ExpandProperty FullName)
foreach($file in $shellFiles){
    $full=[IO.Path]::GetFullPath($file)
    if(-not $full.StartsWith($source+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Shell file escaped checkout'}
    $body=[IO.File]::ReadAllText($full).Replace("`r`n","`n")
    [IO.File]::WriteAllText($full,$body,[Text.UTF8Encoding]::new($false))
}
try {
    & docker run --rm --name $container --label owner=wdttandroid --cpus 8 --memory 12g --mount "type=bind,source=$source,target=/workspace" --mount 'type=volume,source=wdtt-android-build-cache,target=/cache' $image timeout --signal=TERM --kill-after=30s 3600 bash tools/worker-android-build.sh
    $result=$LASTEXITCODE
} finally {
    # This unique name belongs to this invocation, never another project's container.
    & docker rm -f $container 2>$null | Out-Null
}
exit $result
