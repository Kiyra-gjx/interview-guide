param(
    [string]$BaselineReport
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-3-context-set-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-3-context-set-report.md"
$DiffReport = Join-Path $ReportDir "stage-3-context-set-diff.md"

$env:GRADLE_USER_HOME = $GradleUserHome

function Read-Utf8Text {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Get-ResolvedPathOrSelf {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (Test-Path $Path) {
        return (Resolve-Path $Path).Path
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Get-CaseField {
    param($Case, [Parameter(Mandatory = $true)][string]$Field)
    if (-not $Case) {
        return "MISSING"
    }
    return $Case.$Field
}

Push-Location $RepoRoot
try {
    $baselineLabel = $BaselineReport
    $baselineJson = $null

    if ($BaselineReport) {
        if (-not (Test-Path $BaselineReport)) {
            throw "Baseline report not found: $BaselineReport"
        }
        $baselineJson = Read-Utf8Text $BaselineReport
        $baselineResolvedPath = Get-ResolvedPathOrSelf $BaselineReport
        $currentResolvedPath = Get-ResolvedPathOrSelf $CurrentReport
        if ($baselineResolvedPath -eq $currentResolvedPath) {
            $baselineLabel = "$BaselineReport (snapshot before rerun)"
        }
    }

    & .\gradlew.bat :app:agentStage3ContextEval --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not (Test-Path $CurrentReport)) {
        throw "Current report not found: $CurrentReport"
    }

    if (-not (Test-Path $CurrentMarkdown)) {
        throw "Current markdown report not found: $CurrentMarkdown"
    }

    Write-Host "Stage 3 context set report:"
    Write-Host "  $CurrentMarkdown"

    if (-not $BaselineReport) {
        return
    }

    $baseline = $baselineJson | ConvertFrom-Json
    $current = (Read-Utf8Text $CurrentReport) | ConvertFrom-Json

    $metricKeys = @(
        "passedCases",
        "averageRawContextChars",
        "averageAssembledChars",
        "averageCompressionRate",
        "maxCompressionRate",
        "keySectionRetentionRate",
        "brokenRequestCount"
    )

    $lines = @()
    $lines += "# Stage 3 Context Set Diff"
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
    $lines += "| Case | Baseline Raw | Current Raw | Baseline Assembled | Current Assembled | Baseline Compression | Current Compression | Baseline Broken | Current Broken | Changed |"
    $lines += "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"

    $baselineCases = @{}
    foreach ($case in $baseline.caseResults) { $baselineCases[$case.caseId] = $case }
    $currentCases = @{}
    foreach ($case in $current.caseResults) { $currentCases[$case.caseId] = $case }

    $caseIds = @($baselineCases.Keys + $currentCases.Keys | Sort-Object -Unique)
    foreach ($caseId in $caseIds) {
        $baselineCase = $baselineCases[$caseId]
        $currentCase = $currentCases[$caseId]
        $changed = (
            (Get-CaseField $baselineCase "rawContextChars") -ne (Get-CaseField $currentCase "rawContextChars") `
            -or (Get-CaseField $baselineCase "assembledChars") -ne (Get-CaseField $currentCase "assembledChars") `
            -or (Get-CaseField $baselineCase "compressionRate") -ne (Get-CaseField $currentCase "compressionRate") `
            -or (Get-CaseField $baselineCase "requestBroken") -ne (Get-CaseField $currentCase "requestBroken") `
            -or (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed")
        )
        $lines += "| $caseId | $(Get-CaseField $baselineCase 'rawContextChars') | $(Get-CaseField $currentCase 'rawContextChars') | $(Get-CaseField $baselineCase 'assembledChars') | $(Get-CaseField $currentCase 'assembledChars') | $(Get-CaseField $baselineCase 'compressionRate') | $(Get-CaseField $currentCase 'compressionRate') | $(Get-CaseField $baselineCase 'requestBroken') | $(Get-CaseField $currentCase 'requestBroken') | $changed |"
    }

    [System.IO.File]::WriteAllText($DiffReport, ($lines -join [Environment]::NewLine), [System.Text.Encoding]::UTF8)
    Write-Host "Stage 3 context set diff report:"
    Write-Host "  $DiffReport"
}
finally {
    Pop-Location
}
