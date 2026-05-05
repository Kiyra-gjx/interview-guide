param(
    [string]$BaselineReport
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-5-recovery-set-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-5-recovery-set-report.md"
$DiffReport = Join-Path $ReportDir "stage-5-recovery-set-diff.md"

$env:GRADLE_USER_HOME = $GradleUserHome

function Read-Utf8Text {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Get-ResolvedPathOrSelf {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (Test-Path $Path) { return (Resolve-Path $Path).Path }
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Get-CaseField {
    param($Case, [Parameter(Mandatory = $true)][string]$Field)
    if (-not $Case) { return "MISSING" }
    return $Case.$Field
}

Push-Location $RepoRoot
try {
    $baselineLabel = $BaselineReport
    $baselineJson = $null

    if ($BaselineReport) {
        if (-not (Test-Path $BaselineReport)) { throw "Baseline report not found: $BaselineReport" }
        $baselineJson = Read-Utf8Text $BaselineReport
        $baselineResolvedPath = Get-ResolvedPathOrSelf $BaselineReport
        $currentResolvedPath = Get-ResolvedPathOrSelf $CurrentReport
        if ($baselineResolvedPath -eq $currentResolvedPath) {
            $baselineLabel = "$BaselineReport (snapshot before rerun)"
        }
    }

    & .\gradlew.bat :app:agentStage5RecoveryEval --rerun-tasks
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (-not (Test-Path $CurrentReport)) { throw "Current report not found: $CurrentReport" }
    if (-not (Test-Path $CurrentMarkdown)) { throw "Current markdown report not found: $CurrentMarkdown" }

    Write-Host "Stage 5 recovery set report:"
    Write-Host "  $CurrentMarkdown"

    if (-not $BaselineReport) { return }

    $baseline = $baselineJson | ConvertFrom-Json
    $current = (Read-Utf8Text $CurrentReport) | ConvertFrom-Json

    $metricKeys = @(
        "passedCases",
        "recoveryCorrectnessRate",
        "wrongStateContinuedCount",
        "replayedSideEffectCount",
        "coveredRecoveryTypes"
    )

    $lines = @()
    $lines += "# Stage 5 Recovery Set Diff"
    $lines += ""
    $lines += "- baseline: $baselineLabel"
    $lines += "- current: $CurrentReport"
    $lines += ""
    $lines += "| Metric | Baseline | Current | Delta |"
    $lines += "| --- | --- | --- | --- |"

    foreach ($key in $metricKeys) {
        $baselineValue = [double]$baseline.summary.$key
        $currentValue = [double]$current.summary.$key
        $delta = [Math]::Round($currentValue - $baselineValue, 2)
        $lines += "| $key | $baselineValue | $currentValue | $delta |"
    }

    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $lines += "| Case | Baseline Terminal | Current Terminal | Baseline Stop Reason | Current Stop Reason | Baseline Passed | Current Passed | Changed |"
    $lines += "| --- | --- | --- | --- | --- | --- | --- | --- |"

    $baselineCases = @{}
    foreach ($case in $baseline.caseResults) { $baselineCases[$case.caseId] = $case }
    $currentCases = @{}
    foreach ($case in $current.caseResults) { $currentCases[$case.caseId] = $case }
    $caseIds = @($baselineCases.Keys + $currentCases.Keys | Sort-Object -Unique)
    foreach ($caseId in $caseIds) {
        $baselineCase = $baselineCases[$caseId]
        $currentCase = $currentCases[$caseId]
        $changed = (
            (Get-CaseField $baselineCase "actualTerminalState") -ne (Get-CaseField $currentCase "actualTerminalState") `
            -or (Get-CaseField $baselineCase "actualStopReason") -ne (Get-CaseField $currentCase "actualStopReason") `
            -or (Get-CaseField $baselineCase "wrongStateContinued") -ne (Get-CaseField $currentCase "wrongStateContinued") `
            -or (Get-CaseField $baselineCase "replayedSideEffect") -ne (Get-CaseField $currentCase "replayedSideEffect") `
            -or (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed")
        )
        $lines += "| $caseId | $(Get-CaseField $baselineCase 'actualTerminalState') | $(Get-CaseField $currentCase 'actualTerminalState') | $(Get-CaseField $baselineCase 'actualStopReason') | $(Get-CaseField $currentCase 'actualStopReason') | $(Get-CaseField $baselineCase 'passed') | $(Get-CaseField $currentCase 'passed') | $changed |"
    }

    [System.IO.File]::WriteAllText($DiffReport, ($lines -join [Environment]::NewLine), [System.Text.Encoding]::UTF8)
    Write-Host "Stage 5 recovery set diff report:"
    Write-Host "  $DiffReport"
}
finally {
    Pop-Location
}
