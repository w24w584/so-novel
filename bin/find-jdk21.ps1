$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += 'E:\Study\jdk-21*'
$candidates += 'D:\jdk-21*'
$candidates += "$env:ProgramFiles\Java\jdk-21*"
$candidates += "$env:ProgramFiles\Eclipse Adoptium\jdk-21*"

foreach ($pattern in $candidates) {
    $matches = Get-ChildItem $pattern -Directory -ErrorAction SilentlyContinue
    foreach ($dir in $matches) {
        $javaExe = Join-Path $dir.FullName 'bin\java.exe'
        if (Test-Path $javaExe) {
            $ver = & $javaExe -version 2>&1 | Select-Object -First 1
            if ($ver -match 'version "21\.') {
                Write-Output $dir.FullName
                exit 0
            }
        }
    }
}

Write-Error "JDK 21 not found"
exit 1