param(
    [ValidateSet('threads','saturation','discard','futures','dag','starvation','failure','timeout','cancel','virtual','practice','all')]
    [string]$Case = 'threads'
)
$ErrorActionPreference = 'Stop'
$courseClasses = Join-Path $PSScriptRoot '.classes'
$courseJava = (Get-Command java -ErrorAction Stop).Source
$courseJavac = (Get-Command javac -ErrorAction Stop).Source
New-Item -ItemType Directory -Force -Path $courseClasses | Out-Null
& $courseJavac -encoding UTF-8 --release 21 -d $courseClasses (Join-Path $PSScriptRoot 'AsyncLab.java') (Join-Path $PSScriptRoot 'PracticeDag.java')
if ($LASTEXITCODE -ne 0) { throw '编译失败，请确认使用 JDK 21 或更高版本，并查看上方错误。' }
if ($Case -eq 'practice') {
    & $courseJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $courseClasses PracticeDag
    if ($LASTEXITCODE -ne 0) { throw '练习未通过，请根据上方 TODO 或断言信息继续。' }
} else {
    $courseCases = if ($Case -eq 'all') {
        @('threads','saturation','discard','futures','dag','starvation','failure','timeout','cancel','virtual')
    } else { @($Case) }
    foreach ($courseCase in $courseCases) {
        Write-Output "实验：$courseCase"
        & $courseJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $courseClasses AsyncLab $courseCase
        if ($LASTEXITCODE -ne 0) { throw "实验未通过：$courseCase" }
    }
}
