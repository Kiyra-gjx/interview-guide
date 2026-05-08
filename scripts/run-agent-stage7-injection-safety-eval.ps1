param(
    [string]$BaselineReport,
    [switch]$UpdateBaseline
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-7-injection-safety-set-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-7-injection-safety-set-report.md"
$DiffReport = Join-Path $ReportDir "stage-7-injection-safety-set-diff.md"
$EvidenceRoot = Join-Path $RepoRoot "docs\evidence\agent-quantification\stage-7-injection-safety-set"
$EvidenceReportDir = Join-Path $EvidenceRoot "reports"
$EvidenceBaselineDir = Join-Path $EvidenceRoot "baselines"
$EvidenceReport = Join-Path $EvidenceReportDir "stage-7-injection-safety-set-report.json"
$EvidenceMarkdown = Join-Path $EvidenceReportDir "stage-7-injection-safety-set-report.md"
$EvidenceDiff = Join-Path $EvidenceReportDir "stage-7-injection-safety-set-diff.md"
$EvidenceBaseline = Join-Path $EvidenceBaselineDir "stage-7-injection-safety-set-baseline-2026-05-08.json"

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

function Get-RepoRelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $resolvedPath = Get-ResolvedPathOrSelf $Path
    $repoRootPath = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if ($resolvedPath.StartsWith($repoRootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $resolvedPath.Substring($repoRootPath.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    }
    return $Path
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

function Write-Utf8Text {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Text)
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force $parent | Out-Null
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.Encoding]::UTF8)
}

function Copy-EvalArtifact {
    param([Parameter(Mandatory = $true)][string]$Source, [Parameter(Mandatory = $true)][string]$Destination)
    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force $parent | Out-Null
    Copy-Item -Path $Source -Destination $Destination -Force
}

function Format-MarkdownCell {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ([string]$Value -replace '\|', '\|' -replace '\r?\n', ' ').Trim()
}

function New-MarkdownRow {
    param([object[]]$Cells)
    $escapedCells = @()
    foreach ($cell in $Cells) {
        $escapedCells += Format-MarkdownCell $cell
    }
    return "| $($escapedCells -join ' | ') |"
}

function New-MarkdownSeparator {
    param([Parameter(Mandatory = $true)][int]$ColumnCount)
    $cells = @()
    for ($i = 0; $i -lt $ColumnCount; $i++) { $cells += "---" }
    return "| $($cells -join ' | ') |"
}

function Get-RunnerInvocation {
    $parts = @(
        "powershell",
        "-ExecutionPolicy Bypass",
        "-File scripts/run-agent-stage7-injection-safety-eval.ps1"
    )
    if ($BaselineReport) {
        $parts += "-BaselineReport $(Get-RepoRelativePath $BaselineReport)"
    }
    if ($UpdateBaseline) {
        $parts += "-UpdateBaseline"
    }
    return ($parts -join " ")
}

Push-Location $RepoRoot
try {
    $baselineForDiff = $BaselineReport
    $baselineLabel = $BaselineReport
    $baselineJson = $null

    if (-not $baselineForDiff -and (Test-Path $EvidenceBaseline)) {
        $baselineForDiff = $EvidenceBaseline
        $baselineLabel = Get-RepoRelativePath $EvidenceBaseline
    }

    if ($baselineForDiff) {
        if (-not (Test-Path $baselineForDiff)) {
            throw "Baseline report not found: $baselineForDiff"
        }

        $baselineJson = Read-Utf8Text $baselineForDiff
        $baselineResolvedPath = Get-ResolvedPathOrSelf $baselineForDiff
        $currentResolvedPath = Get-ResolvedPathOrSelf $CurrentReport
        if ($baselineResolvedPath -eq $currentResolvedPath) {
            $baselineLabel = "$baselineForDiff (snapshot before rerun)"
        }
        if ($UpdateBaseline -and $baselineForDiff -eq $EvidenceBaseline) {
            $baselineLabel = "$baselineLabel (snapshot before baseline update)"
        }
    }

    & .\gradlew.bat :app:agentStage7InjectionSafetyEval --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not (Test-Path $CurrentReport)) {
        throw "Current report not found: $CurrentReport"
    }
    if (-not (Test-Path $CurrentMarkdown)) {
        throw "Current markdown report not found: $CurrentMarkdown"
    }

    Copy-EvalArtifact -Source $CurrentReport -Destination $EvidenceReport
    Copy-EvalArtifact -Source $CurrentMarkdown -Destination $EvidenceMarkdown

    $current = (Read-Utf8Text $CurrentReport) | ConvertFrom-Json
    if (-not (Test-Path $EvidenceRoot)) {
        New-Item -ItemType Directory -Force $EvidenceRoot | Out-Null
    }

    $rawResultsPath = Join-Path $EvidenceRoot "raw-results.md"
    $lines = @()
    $lines += "# stage-7-injection-safety-set Raw Results"
    $lines += ""
    $lines += "## Run Info"
    $lines += ""
    $lines += "- executedAt: ``$($current.generatedAt)``"
    $lines += "- runner: ``$(Get-RunnerInvocation)``"
    $lines += "- reportPath: ``reports/stage-7-injection-safety-set-report.json``"
    $lines += "- diffPath: ``reports/stage-7-injection-safety-set-diff.md``"
    $lines += "- archivedBaselinePath: ``baselines/stage-7-injection-safety-set-baseline-2026-05-08.json``"
    if ($baselineJson) {
        $lines += "- diffBaseline: ``$baselineLabel``"
    }
    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $headers = @("caseId","scenarioType","attackCarrier","completion","approval","guardrailHit","secretLeak","approvalBypass","unsafeToolExecution","unsafeDirectAnswer","degraded","modelPollution","runtimeBoundaryEvidence","passed")
    $lines += New-MarkdownRow -Cells $headers
    $lines += New-MarkdownSeparator -ColumnCount $headers.Count
    foreach ($case in $current.caseResults) {
        $lines += New-MarkdownRow -Cells @(
            $case.caseId,
            $case.scenarioType,
            $case.attackCarrier,
            $case.actualCompletionMode,
            $case.actualApprovalStatus,
            $case.guardrailHit,
            $case.secretLeaked,
            $case.approvalBypassed,
            $case.unsafeToolExecution,
            $case.unsafeDirectAnswer,
            $case.degraded,
            $case.modelPollutionSimulated,
            $case.runtimeBoundaryEvidence,
            $case.passed
        )
    }
    Write-Utf8Text -Path $rawResultsPath -Text ($lines -join [Environment]::NewLine)

    $summaryPath = Join-Path $EvidenceRoot "summary.md"
    $summaryLines = @()
    $summaryLines += "# stage-7-injection-safety-set Summary"
    $summaryLines += ""
    $summaryLines += "## Core Metrics"
    $summaryLines += ""
    $summaryLines += "- totalCases: ``$($current.summary.totalCases)``"
    $summaryLines += "- passedCases: ``$($current.summary.passedCases)``"
    $summaryLines += "- attackBlockedCases: ``$($current.summary.attackBlockedCases)``"
    $summaryLines += "- secretLeakCount: ``$($current.summary.secretLeakCount)``"
    $summaryLines += "- approvalBypassCount: ``$($current.summary.approvalBypassCount)``"
    $summaryLines += "- unsafeToolExecutionCount: ``$($current.summary.unsafeToolExecutionCount)``"
    $summaryLines += "- unsafeDirectAnswerCount: ``$($current.summary.unsafeDirectAnswerCount)``"
    $summaryLines += "- degradedCases: ``$($current.summary.degradedCases)``"
    $summaryLines += "- modelPollutionSimulatedCases: ``$($current.summary.modelPollutionSimulatedCases)``"
    $summaryLines += "- runtimeBoundaryEvidenceCases: ``$($current.summary.runtimeBoundaryEvidenceCases)``"
    $summaryLines += ""
    $summaryLines += "## Resume Gate"
    $summaryLines += ""
    $summaryLines += "- fixedSampleSet: ``yes``"
    $summaryLines += "- unifiedMetricDefinition: ``yes``"
    $summaryLines += "- rawRecords: ``yes``"
    $summaryLines += "- reviewableEvidence: ``yes``"
    $summaryLines += "- resumeSafe: ``partial``"
    Write-Utf8Text -Path $summaryPath -Text ($summaryLines -join [Environment]::NewLine)

    if ($UpdateBaseline -or -not (Test-Path $EvidenceBaseline)) {
        Copy-EvalArtifact -Source $CurrentReport -Destination $EvidenceBaseline
    }

    if (-not $baselineJson -and (Test-Path $EvidenceBaseline)) {
        $baselineJson = Read-Utf8Text $EvidenceBaseline
        $baselineLabel = Get-RepoRelativePath $EvidenceBaseline
    }

    if (-not $baselineJson) {
        return
    }

    $baseline = $baselineJson | ConvertFrom-Json
    $metricKeys = @("passedCases","attackBlockedCases","secretLeakCount","approvalBypassCount","unsafeToolExecutionCount","unsafeDirectAnswerCount","degradedCases","modelPollutionSimulatedCases","runtimeBoundaryEvidenceCases")
    $diffLines = @()
    $diffLines += "# Stage 7 Injection Safety Set Diff"
    $diffLines += ""
    $diffLines += "- baseline: $baselineLabel"
    $diffLines += "- current: $(Get-RepoRelativePath $CurrentReport)"
    $diffLines += ""
    $diffLines += "| Metric | Baseline | Current | Delta |"
    $diffLines += "| --- | --- | --- | --- |"
    foreach ($key in $metricKeys) {
        $baselineValue = [double]$baseline.summary.$key
        $currentValue = [double]$current.summary.$key
        $delta = [Math]::Round($currentValue - $baselineValue, 2)
        $diffLines += "| $key | $baselineValue | $currentValue | $delta |"
    }
    $diffLines += ""
    $diffLines += "## Case Results"
    $diffLines += ""
    $headers = @("Case","Baseline Passed","Current Passed","Baseline Completion","Current Completion","Baseline Approval","Current Approval","Baseline Guardrail","Current Guardrail","Baseline AttackBlocked","Current AttackBlocked","Baseline SecretLeak","Current SecretLeak","Baseline UnsafeTool","Current UnsafeTool","Baseline UnsafeAnswer","Current UnsafeAnswer","Baseline ModelPollution","Current ModelPollution","Baseline RuntimeBoundary","Current RuntimeBoundary","Changed")
    $diffLines += New-MarkdownRow -Cells $headers
    $diffLines += New-MarkdownSeparator -ColumnCount $headers.Count

    $baselineCases = @{}
    foreach ($case in $baseline.caseResults) { $baselineCases[$case.caseId] = $case }
    $currentCases = @{}
    foreach ($case in $current.caseResults) { $currentCases[$case.caseId] = $case }
    $caseIds = @($baselineCases.Keys + $currentCases.Keys | Sort-Object -Unique)
    foreach ($caseId in $caseIds) {
        $baselineCase = $baselineCases[$caseId]
        $currentCase = $currentCases[$caseId]
        $changed = (
            (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed") `
            -or (Get-CaseField $baselineCase "actualCompletionMode") -ne (Get-CaseField $currentCase "actualCompletionMode") `
            -or (Get-CaseField $baselineCase "actualApprovalStatus") -ne (Get-CaseField $currentCase "actualApprovalStatus") `
            -or (Get-CaseField $baselineCase "guardrailHit") -ne (Get-CaseField $currentCase "guardrailHit") `
            -or (Get-CaseField $baselineCase "attackBlocked") -ne (Get-CaseField $currentCase "attackBlocked") `
            -or (Get-CaseField $baselineCase "secretLeaked") -ne (Get-CaseField $currentCase "secretLeaked") `
            -or (Get-CaseField $baselineCase "unsafeToolExecution") -ne (Get-CaseField $currentCase "unsafeToolExecution") `
            -or (Get-CaseField $baselineCase "unsafeDirectAnswer") -ne (Get-CaseField $currentCase "unsafeDirectAnswer") `
            -or (Get-CaseField $baselineCase "modelPollutionSimulated") -ne (Get-CaseField $currentCase "modelPollutionSimulated") `
            -or (Get-CaseField $baselineCase "runtimeBoundaryEvidence") -ne (Get-CaseField $currentCase "runtimeBoundaryEvidence")
        )
        $diffLines += New-MarkdownRow -Cells @(
            $caseId,
            (Get-CaseField $baselineCase "passed"),
            (Get-CaseField $currentCase "passed"),
            (Get-CaseField $baselineCase "actualCompletionMode"),
            (Get-CaseField $currentCase "actualCompletionMode"),
            (Get-CaseField $baselineCase "actualApprovalStatus"),
            (Get-CaseField $currentCase "actualApprovalStatus"),
            (Get-CaseField $baselineCase "guardrailHit"),
            (Get-CaseField $currentCase "guardrailHit"),
            (Get-CaseField $baselineCase "attackBlocked"),
            (Get-CaseField $currentCase "attackBlocked"),
            (Get-CaseField $baselineCase "secretLeaked"),
            (Get-CaseField $currentCase "secretLeaked"),
            (Get-CaseField $baselineCase "unsafeToolExecution"),
            (Get-CaseField $currentCase "unsafeToolExecution"),
            (Get-CaseField $baselineCase "unsafeDirectAnswer"),
            (Get-CaseField $currentCase "unsafeDirectAnswer"),
            (Get-CaseField $baselineCase "modelPollutionSimulated"),
            (Get-CaseField $currentCase "modelPollutionSimulated"),
            (Get-CaseField $baselineCase "runtimeBoundaryEvidence"),
            (Get-CaseField $currentCase "runtimeBoundaryEvidence"),
            $changed
        )
    }
    Write-Utf8Text -Path $DiffReport -Text ($diffLines -join [Environment]::NewLine)
    Copy-EvalArtifact -Source $DiffReport -Destination $EvidenceDiff
}
finally {
    Pop-Location
}
