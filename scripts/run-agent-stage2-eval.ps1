param(
    [string]$BaselineReport
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-2-regression-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-2-regression-report.md"
$DiffReport = Join-Path $ReportDir "stage-2-regression-diff.md"

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

    & .\gradlew.bat :app:agentStage2Eval --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not (Test-Path $CurrentReport)) {
        throw "Current report not found: $CurrentReport"
    }

    if (-not (Test-Path $CurrentMarkdown)) {
        throw "Current markdown report not found: $CurrentMarkdown"
    }

    Write-Host "Stage 2 eval report:"
    Write-Host "  $CurrentMarkdown"

    if (-not $BaselineReport) {
        return
    }

    $baseline = $baselineJson | ConvertFrom-Json
    $current = (Read-Utf8Text $CurrentReport) | ConvertFrom-Json

    $metricKeys = @(
        "successRate",
        "degradedRate",
        "waitingApprovalRate",
        "errorRate",
        "averageLatencyMs",
        "maxLatencyMs",
        "guardrailHitCases",
        "passedCases"
    )

    $lines = @()
    $lines += "# Stage 2 Agent Eval Diff"
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
    $lines += "## Approval Status"
    $lines += ""
    $lines += "| Status | Baseline | Current | Delta |"
    $lines += "| --- | --- | --- | --- |"

    $statuses = @{}
    foreach ($property in $baseline.summary.approvalStatusCounts.PSObject.Properties.Name) {
        $statuses[$property] = $true
    }
    foreach ($property in $current.summary.approvalStatusCounts.PSObject.Properties.Name) {
        $statuses[$property] = $true
    }

    foreach ($status in ($statuses.Keys | Sort-Object)) {
        $baselineValue = 0
        $currentValue = 0
        if ($baseline.summary.approvalStatusCounts.PSObject.Properties.Name -contains $status) {
            $baselineValue = [int]$baseline.summary.approvalStatusCounts.$status
        }
        if ($current.summary.approvalStatusCounts.PSObject.Properties.Name -contains $status) {
            $currentValue = [int]$current.summary.approvalStatusCounts.$status
        }
        $delta = $currentValue - $baselineValue
        $lines += "| $status | $baselineValue | $currentValue | $delta |"
    }

    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $lines += "| Case | Baseline Outcome | Current Outcome | Baseline Passed | Current Passed | Changed |"
    $lines += "| --- | --- | --- | --- | --- | --- |"

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

        $baselineOutcome = if ($baselineCase) { $baselineCase.actualOutcome } else { "MISSING" }
        $currentOutcome = if ($currentCase) { $currentCase.actualOutcome } else { "MISSING" }
        $baselinePassed = if ($baselineCase) { $baselineCase.passed } else { "MISSING" }
        $currentPassed = if ($currentCase) { $currentCase.passed } else { "MISSING" }
        $changed = (
            (Get-CaseField $baselineCase "actualOutcome") -ne (Get-CaseField $currentCase "actualOutcome") `
            -or (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed") `
            -or (Get-CaseField $baselineCase "actualCompletionMode") -ne (Get-CaseField $currentCase "actualCompletionMode") `
            -or (Get-CaseField $baselineCase "actualTurnStatus") -ne (Get-CaseField $currentCase "actualTurnStatus") `
            -or (Get-CaseField $baselineCase "actualApprovalStatus") -ne (Get-CaseField $currentCase "actualApprovalStatus") `
            -or (Get-CaseField $baselineCase "actualGuardrailCount") -ne (Get-CaseField $currentCase "actualGuardrailCount") `
            -or (Get-CaseField $baselineCase "actualErrorCode") -ne (Get-CaseField $currentCase "actualErrorCode")
        )

        $lines += "| $caseId | $baselineOutcome | $currentOutcome | $baselinePassed | $currentPassed | $changed |"
    }

    [System.IO.File]::WriteAllText(
        $DiffReport,
        ($lines -join [Environment]::NewLine),
        [System.Text.Encoding]::UTF8
    )
    Write-Host "Stage 2 eval diff report:"
    Write-Host "  $DiffReport"
}
finally {
    Pop-Location
}
