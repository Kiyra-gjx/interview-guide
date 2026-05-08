param(
    [string]$BaselineReport,
    [switch]$UpdateBaseline
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-7-tool-routing-set-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-7-tool-routing-set-report.md"
$DiffReport = Join-Path $ReportDir "stage-7-tool-routing-set-diff.md"
$EvidenceRoot = Join-Path $RepoRoot "docs\evidence\agent-quantification\stage-7-tool-routing-set"
$EvidenceReportDir = Join-Path $EvidenceRoot "reports"
$EvidenceBaselineDir = Join-Path $EvidenceRoot "baselines"
$EvidenceReport = Join-Path $EvidenceReportDir "stage-7-tool-routing-set-report.json"
$EvidenceMarkdown = Join-Path $EvidenceReportDir "stage-7-tool-routing-set-report.md"
$EvidenceDiff = Join-Path $EvidenceReportDir "stage-7-tool-routing-set-diff.md"
$EvidenceBaseline = Join-Path $EvidenceBaselineDir "stage-7-tool-routing-set-baseline-2026-05-08.json"

$env:GRADLE_USER_HOME = $GradleUserHome

function Read-Utf8Text {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
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

function Get-CaseField {
    param(
        $Case,
        [Parameter(Mandatory = $true)][string]$Field
    )
    if (-not $Case) { return "MISSING" }
    return $Case.$Field
}

function Get-RunnerInvocation {
    $parts = @(
        "powershell",
        "-ExecutionPolicy Bypass",
        "-File scripts/run-agent-stage7-tool-routing-eval.ps1"
    )
    if ($BaselineReport) {
        $parts += "-BaselineReport $(Get-RepoRelativePath $BaselineReport)"
    }
    if ($UpdateBaseline) {
        $parts += "-UpdateBaseline"
    }
    return ($parts -join " ")
}

function To-CompactJson {
    param($Value)
    if ($null -eq $Value) {
        return "{}"
    }
    return ($Value | ConvertTo-Json -Depth 12 -Compress)
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

    & .\gradlew.bat :app:agentStage7ToolRoutingEval --rerun-tasks
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
    $lines += "# stage-7-tool-routing-set Raw Results"
    $lines += ""
    $lines += "## Run Info"
    $lines += ""
    $lines += "- executedAt: ``$($current.generatedAt)``"
    $lines += "- runner: ``$(Get-RunnerInvocation)``"
    $lines += "- reportPath: ``reports/stage-7-tool-routing-set-report.json``"
    $lines += "- diffPath: ``reports/stage-7-tool-routing-set-diff.md``"
    $lines += "- archivedBaselinePath: ``baselines/stage-7-tool-routing-set-baseline-2026-05-08.json``"
    if ($baselineJson) {
        $lines += "- diffBaseline: ``$baselineLabel``"
    }
    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $headers = @(
        "caseId",
        "caseType",
        "expectedOutcome",
        "actualOutcome",
        "expectedTool",
        "actualTool",
        "paramMatched",
        "rejectionMatched",
        "directReplyMatched",
        "approvalRoutingMatched",
        "unexpectedToolExecution",
        "passed"
    )
    $lines += New-MarkdownRow -Cells $headers
    $lines += New-MarkdownSeparator -ColumnCount $headers.Count
    foreach ($case in $current.caseResults) {
        $lines += New-MarkdownRow -Cells @(
            $case.caseId,
            $case.caseType,
            $case.expectedOutcome,
            $case.actualOutcome,
            $case.expectedTool,
            $case.actualTool,
            $case.paramMatched,
            $case.rejectionMatched,
            $case.directReplyMatched,
            $case.approvalRoutingMatched,
            $case.unexpectedToolExecution,
            $case.passed
        )
    }
    Write-Utf8Text -Path $rawResultsPath -Text ($lines -join [Environment]::NewLine)

    $summaryPath = Join-Path $EvidenceRoot "summary.md"
    $summaryLines = @()
    $summaryLines += "# stage-7-tool-routing-set Summary"
    $summaryLines += ""
    $summaryLines += "## Core Metrics"
    $summaryLines += ""
    $summaryLines += "- totalCases: ``$($current.summary.totalCases)``"
    $summaryLines += "- passedCases: ``$($current.summary.passedCases)``"
    $summaryLines += "- toolSelectionAccuracy: ``$($current.summary.toolSelectionAccuracy)%``"
    $summaryLines += "- paramAccuracy: ``$($current.summary.paramAccuracy)%``"
    $summaryLines += "- rejectionAccuracy: ``$($current.summary.rejectionAccuracy)%``"
    $summaryLines += "- directReplyAccuracy: ``$($current.summary.directReplyAccuracy)%``"
    $summaryLines += "- approvalRoutingAccuracy: ``$($current.summary.approvalRoutingAccuracy)%``"
    $summaryLines += "- unexpectedToolExecutionCount: ``$($current.summary.unexpectedToolExecutionCount)``"
    $summaryLines += "- decisionFixtureMode: ``fixed_structured_decision``"
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
    $metricKeys = @(
        "passedCases",
        "toolSelectionAccuracy",
        "paramAccuracy",
        "rejectionAccuracy",
        "directReplyAccuracy",
        "approvalRoutingAccuracy",
        "unexpectedToolExecutionCount"
    )
    $diffLines = @()
    $diffLines += "# Stage 7 Tool Routing Set Diff"
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
    $diffLines += "- latency fields are excluded from diff metrics because they are local runtime noise."
    $diffLines += ""
    $diffLines += "## Case Results"
    $diffLines += ""
    $headers = @(
        "Case",
        "Baseline Type",
        "Current Type",
        "Baseline ExpectedOutcome",
        "Current ExpectedOutcome",
        "Baseline ExpectedTool",
        "Current ExpectedTool",
        "Baseline ExpectedParams",
        "Current ExpectedParams",
        "Baseline Passed",
        "Current Passed",
        "Baseline Outcome",
        "Current Outcome",
        "Baseline Tool",
        "Current Tool",
        "Baseline RejectionReason",
        "Current RejectionReason",
        "Baseline Params",
        "Current Params",
        "Baseline Approval",
        "Current Approval",
        "Baseline UnexpectedExec",
        "Current UnexpectedExec",
        "Changed"
    )
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
        $baselineExpectedParams = To-CompactJson (Get-CaseField $baselineCase "expectedParams")
        $currentExpectedParams = To-CompactJson (Get-CaseField $currentCase "expectedParams")
        $baselineParams = To-CompactJson (Get-CaseField $baselineCase "actualParams")
        $currentParams = To-CompactJson (Get-CaseField $currentCase "actualParams")
        $changed = (
            (Get-CaseField $baselineCase "caseType") -ne (Get-CaseField $currentCase "caseType") `
            -or (Get-CaseField $baselineCase "expectedOutcome") -ne (Get-CaseField $currentCase "expectedOutcome") `
            -or (Get-CaseField $baselineCase "expectedTool") -ne (Get-CaseField $currentCase "expectedTool") `
            -or $baselineExpectedParams -ne $currentExpectedParams `
            -or (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed") `
            -or (Get-CaseField $baselineCase "actualOutcome") -ne (Get-CaseField $currentCase "actualOutcome") `
            -or (Get-CaseField $baselineCase "actualTool") -ne (Get-CaseField $currentCase "actualTool") `
            -or (Get-CaseField $baselineCase "rejectionReason") -ne (Get-CaseField $currentCase "rejectionReason") `
            -or $baselineParams -ne $currentParams `
            -or (Get-CaseField $baselineCase "approvalStatus") -ne (Get-CaseField $currentCase "approvalStatus") `
            -or (Get-CaseField $baselineCase "unexpectedToolExecution") -ne (Get-CaseField $currentCase "unexpectedToolExecution")
        )
        $diffLines += New-MarkdownRow -Cells @(
            $caseId,
            (Get-CaseField $baselineCase "caseType"),
            (Get-CaseField $currentCase "caseType"),
            (Get-CaseField $baselineCase "expectedOutcome"),
            (Get-CaseField $currentCase "expectedOutcome"),
            (Get-CaseField $baselineCase "expectedTool"),
            (Get-CaseField $currentCase "expectedTool"),
            $baselineExpectedParams,
            $currentExpectedParams,
            (Get-CaseField $baselineCase "passed"),
            (Get-CaseField $currentCase "passed"),
            (Get-CaseField $baselineCase "actualOutcome"),
            (Get-CaseField $currentCase "actualOutcome"),
            (Get-CaseField $baselineCase "actualTool"),
            (Get-CaseField $currentCase "actualTool"),
            (Get-CaseField $baselineCase "rejectionReason"),
            (Get-CaseField $currentCase "rejectionReason"),
            $baselineParams,
            $currentParams,
            (Get-CaseField $baselineCase "approvalStatus"),
            (Get-CaseField $currentCase "approvalStatus"),
            (Get-CaseField $baselineCase "unexpectedToolExecution"),
            (Get-CaseField $currentCase "unexpectedToolExecution"),
            $changed
        )
    }
    Write-Utf8Text -Path $DiffReport -Text ($diffLines -join [Environment]::NewLine)
    Copy-EvalArtifact -Source $DiffReport -Destination $EvidenceDiff
}
finally {
    Pop-Location
}
