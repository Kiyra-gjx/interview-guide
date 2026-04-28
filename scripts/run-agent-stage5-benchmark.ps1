param(
    [string]$BaselineReport
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-5-benchmark-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-5-benchmark-report.md"
$DiffReport = Join-Path $ReportDir "stage-5-benchmark-diff.md"

$env:GRADLE_USER_HOME = $GradleUserHome

function Read-Utf8Text {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Get-ResolvedPathOrSelf {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (Test-Path $Path) {
        return (Resolve-Path $Path).Path
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Get-CaseField {
    param(
        $Case,
        [Parameter(Mandatory = $true)]
        [string]$Field
    )

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

    & .\gradlew.bat :app:agentStage5Benchmark --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not (Test-Path $CurrentReport)) {
        throw "Current report not found: $CurrentReport"
    }

    if (-not (Test-Path $CurrentMarkdown)) {
        throw "Current markdown report not found: $CurrentMarkdown"
    }

    Write-Host "Stage 5 benchmark report:"
    Write-Host "  $CurrentMarkdown"

    if (-not $BaselineReport) {
        return
    }

    $baseline = $baselineJson | ConvertFrom-Json
    $current = (Read-Utf8Text $CurrentReport) | ConvertFrom-Json

    $metricKeys = @(
        "passedCases",
        "multiStepCases",
        "averageExecutedSteps",
        "averageLatencyMs",
        "maxLatencyMs",
        "exhaustedCases",
        "handoffAcceptedCases",
        "handoffRejectedCases",
        "replayBlockedCases"
    )

    $lines = @()
    $lines += "# Stage 5 Agent Benchmark Diff"
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
    $lines += "## Stop Reasons"
    $lines += ""
    $lines += "| Stop Reason | Baseline | Current | Delta |"
    $lines += "| --- | --- | --- | --- |"

    $stopReasons = @{}
    foreach ($property in $baseline.summary.stopReasonCounts.PSObject.Properties.Name) {
        $stopReasons[$property] = $true
    }
    foreach ($property in $current.summary.stopReasonCounts.PSObject.Properties.Name) {
        $stopReasons[$property] = $true
    }

    foreach ($stopReason in ($stopReasons.Keys | Sort-Object)) {
        $baselineValue = 0
        $currentValue = 0
        if ($baseline.summary.stopReasonCounts.PSObject.Properties.Name -contains $stopReason) {
            $baselineValue = [int]$baseline.summary.stopReasonCounts.$stopReason
        }
        if ($current.summary.stopReasonCounts.PSObject.Properties.Name -contains $stopReason) {
            $currentValue = [int]$current.summary.stopReasonCounts.$stopReason
        }
        $delta = $currentValue - $baselineValue
        $lines += "| $stopReason | $baselineValue | $currentValue | $delta |"
    }

    $lines += ""
    $lines += "## Terminal States"
    $lines += ""
    $lines += "| Terminal State | Baseline | Current | Delta |"
    $lines += "| --- | --- | --- | --- |"

    $terminalStates = @{}
    foreach ($property in $baseline.summary.terminalStateCounts.PSObject.Properties.Name) {
        $terminalStates[$property] = $true
    }
    foreach ($property in $current.summary.terminalStateCounts.PSObject.Properties.Name) {
        $terminalStates[$property] = $true
    }

    foreach ($terminalState in ($terminalStates.Keys | Sort-Object)) {
        $baselineValue = 0
        $currentValue = 0
        if ($baseline.summary.terminalStateCounts.PSObject.Properties.Name -contains $terminalState) {
            $baselineValue = [int]$baseline.summary.terminalStateCounts.$terminalState
        }
        if ($current.summary.terminalStateCounts.PSObject.Properties.Name -contains $terminalState) {
            $currentValue = [int]$current.summary.terminalStateCounts.$terminalState
        }
        $delta = $currentValue - $baselineValue
        $lines += "| $terminalState | $baselineValue | $currentValue | $delta |"
    }

    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $lines += "| Case | Baseline Stop Reason | Current Stop Reason | Baseline Terminal | Current Terminal | Baseline Passed | Current Passed | Changed |"
    $lines += "| --- | --- | --- | --- | --- | --- | --- | --- |"

    $baselineCases = @{}
    foreach ($case in $baseline.caseResults) {
        $baselineCases[$case.caseId] = $case
    }

    $currentCases = @{}
    foreach ($case in $current.caseResults) {
        $currentCases[$case.caseId] = $case
    }

    $caseIds = @($baselineCases.Keys + $currentCases.Keys | Sort-Object -Unique)
    foreach ($caseId in $caseIds) {
        $baselineCase = $baselineCases[$caseId]
        $currentCase = $currentCases[$caseId]

        $baselineStopReason = if ($baselineCase) { $baselineCase.actualStopReason } else { "MISSING" }
        $currentStopReason = if ($currentCase) { $currentCase.actualStopReason } else { "MISSING" }
        $baselineTerminal = if ($baselineCase) { $baselineCase.actualTerminalState } else { "MISSING" }
        $currentTerminal = if ($currentCase) { $currentCase.actualTerminalState } else { "MISSING" }
        $baselinePassed = if ($baselineCase) { $baselineCase.passed } else { "MISSING" }
        $currentPassed = if ($currentCase) { $currentCase.passed } else { "MISSING" }
        $changed = (
            (Get-CaseField $baselineCase "actualStopReason") -ne (Get-CaseField $currentCase "actualStopReason") `
            -or (Get-CaseField $baselineCase "actualBudgetStopReason") -ne (Get-CaseField $currentCase "actualBudgetStopReason") `
            -or (Get-CaseField $baselineCase "actualTerminalState") -ne (Get-CaseField $currentCase "actualTerminalState") `
            -or (Get-CaseField $baselineCase "actualExecutedSteps") -ne (Get-CaseField $currentCase "actualExecutedSteps") `
            -or (Get-CaseField $baselineCase "actualMultiStepEnabled") -ne (Get-CaseField $currentCase "actualMultiStepEnabled") `
            -or (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed")
        )

        $lines += "| $caseId | $baselineStopReason | $currentStopReason | $baselineTerminal | $currentTerminal | $baselinePassed | $currentPassed | $changed |"
    }

    [System.IO.File]::WriteAllText(
        $DiffReport,
        ($lines -join [Environment]::NewLine),
        [System.Text.Encoding]::UTF8
    )
    Write-Host "Stage 5 benchmark diff report:"
    Write-Host "  $DiffReport"
}
finally {
    Pop-Location
}
