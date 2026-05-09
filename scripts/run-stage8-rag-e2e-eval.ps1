param(
    [string]$BaselineReport,
    [switch]$UpdateBaseline,
    [switch]$SkipRealRun,
    [int]$CaseLimit = 0,
    [string]$MaxHeap = "4g"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-8-rag-e2e-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-8-rag-e2e-report.md"
$DiffReport = Join-Path $ReportDir "stage-8-rag-e2e-diff.md"
$EvidenceRoot = Join-Path $RepoRoot "docs\evidence\agent-quantification\stage-8-rag-e2e"
$EvidenceReportDir = Join-Path $EvidenceRoot "reports"
$EvidenceBaselineDir = Join-Path $EvidenceRoot "baselines"
$EvidenceReport = Join-Path $EvidenceReportDir "stage-8-rag-e2e-report.json"
$EvidenceMarkdown = Join-Path $EvidenceReportDir "stage-8-rag-e2e-report.md"
$EvidenceDiff = Join-Path $EvidenceReportDir "stage-8-rag-e2e-diff.md"
$EvidenceBaseline = Join-Path $EvidenceBaselineDir "stage-8-rag-e2e-baseline-2026-05-09.json"

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

function Get-RunnerInvocation {
    $parts = @(
        "powershell",
        "-ExecutionPolicy Bypass",
        "-File scripts/run-stage8-rag-e2e-eval.ps1"
    )
    if ($BaselineReport) {
        $parts += "-BaselineReport $(Get-RepoRelativePath $BaselineReport)"
    }
    if ($UpdateBaseline) {
        $parts += "-UpdateBaseline"
    }
    if ($SkipRealRun) {
        $parts += "-SkipRealRun"
    }
    if ($CaseLimit -gt 0) {
        $parts += "-CaseLimit $CaseLimit"
    }
    if ($MaxHeap) {
        $parts += "-MaxHeap $MaxHeap"
    }
    return ($parts -join " ")
}

function Write-RawResults {
    param($Report, [string]$DiffBaselineLabel)

    $rawResultsPath = Join-Path $EvidenceRoot "raw-results.md"
    $lines = @()
    $lines += "# stage-8-rag-e2e Raw Results"
    $lines += ""
    $lines += "## Run Info"
    $lines += ""
    $lines += "- executedAt: ``$($Report.generatedAt)``"
    $lines += "- runner: ``$(Get-RunnerInvocation)``"
    $lines += "- reportPath: ``reports/stage-8-rag-e2e-report.json``"
    $lines += "- diffPath: ``reports/stage-8-rag-e2e-diff.md``"
    $lines += "- archivedBaselinePath: ``baselines/stage-8-rag-e2e-baseline-2026-05-09.json``"
    if ($DiffBaselineLabel) {
        $lines += "- diffBaseline: ``$DiffBaselineLabel``"
    }
    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $headers = @("caseId", "queryType", "answerable", "recallAt3", "hitRateAt3", "mrr", "ndcgAt3", "correctness", "faithfulness", "noAnswerMatched", "latencyMs", "passed")
    $lines += New-MarkdownRow -Cells $headers
    $lines += New-MarkdownSeparator -ColumnCount $headers.Count
    foreach ($case in $Report.caseResults) {
        $lines += New-MarkdownRow -Cells @(
            $case.caseId,
            $case.queryType,
            $case.answerable,
            $case.retrievalMetrics.recallAt3,
            $case.retrievalMetrics.hitRateAt3,
            $case.retrievalMetrics.mrr,
            $case.retrievalMetrics.ndcgAt3,
            $case.generationScores.correctness,
            $case.generationScores.faithfulness,
            $case.noAnswerMatched,
            $case.latencyMs,
            $case.passed
        )
    }
    Write-Utf8Text -Path $rawResultsPath -Text ($lines -join [Environment]::NewLine)
}

function Write-Summary {
    param($Report)

    $summaryPath = Join-Path $EvidenceRoot "summary.md"
    $lines = @()
    $lines += "# stage-8-rag-e2e Summary"
    $lines += ""
    $lines += "## Core Metrics"
    $lines += ""
    $lines += "- totalQueries: ``$($Report.summary.totalQueries)``"
    $lines += "- passedQueries: ``$($Report.summary.passedQueries)``"
    $lines += "- answerableQueries: ``$($Report.summary.answerableQueries)``"
    $lines += "- noAnswerQueries: ``$($Report.summary.noAnswerQueries)``"
    $lines += "- recallAt3: ``$($Report.summary.recallAt3)``"
    $lines += "- recallAt5: ``$($Report.summary.recallAt5)``"
    $lines += "- recallAt10: ``$($Report.summary.recallAt10)``"
    $lines += "- hitRateAt3: ``$($Report.summary.hitRateAt3)``"
    $lines += "- mrr: ``$($Report.summary.mrr)``"
    $lines += "- ndcgAt3: ``$($Report.summary.ndcgAt3)``"
    $lines += "- correctness: ``$($Report.summary.correctness)``"
    $lines += "- attribution: ``$($Report.summary.attribution)``"
    $lines += "- completeness: ``$($Report.summary.completeness)``"
    $lines += "- faithfulness: ``$($Report.summary.faithfulness)``"
    $lines += "- readability: ``$($Report.summary.readability)``"
    $lines += "- latencyP50Ms: ``$($Report.summary.latencyP50Ms)``"
    $lines += "- latencyP95Ms: ``$($Report.summary.latencyP95Ms)``"
    $lines += "- latencyP99Ms: ``$($Report.summary.latencyP99Ms)``"
    $lines += ""
    $lines += "## Resume Gate"
    $lines += ""
    $lines += "- realPgvectorPath: ``yes``"
    $lines += "- realEmbeddingPath: ``yes``"
    $lines += "- realLlmGenerationPath: ``yes``"
    $lines += "- gradedRelevance: ``yes``"
    $lines += "- baselineComparable: ``yes``"
    $lines += "- resumeSafe: ``partial``"
    $lines += ""
    $lines += "## Do Not Claim"
    $lines += ""
    $lines += "- Do not claim production RAG accuracy from this suite."
    $lines += "- Do not claim public benchmark results."
    $lines += "- Do not compare local single-machine latency to online performance."
    Write-Utf8Text -Path $summaryPath -Text ($lines -join [Environment]::NewLine)
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
    }

    if (-not $SkipRealRun) {
        if ([string]::IsNullOrWhiteSpace($env:AI_BAILIAN_API_KEY)) {
            throw "AI_BAILIAN_API_KEY is required for the real Stage 8 RAG E2E eval. PostgreSQL/pgvector may be ready, but embedding and LLM calls cannot run without this key."
        }
        $env:APP_STAGE8_RAG_E2E_ENABLED = "true"
        $env:APP_STAGE8_RAG_E2E_MAX_HEAP = $MaxHeap
        if ($CaseLimit -gt 0) {
            $env:APP_STAGE8_RAG_E2E_CASE_LIMIT = [string]$CaseLimit
        } else {
            Remove-Item Env:APP_STAGE8_RAG_E2E_CASE_LIMIT -ErrorAction SilentlyContinue
        }
        & .\gradlew.bat :app:agentStage8RagE2eEval --rerun-tasks
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
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
    Write-RawResults -Report $current -DiffBaselineLabel $baselineLabel
    Write-Summary $current

    if ($UpdateBaseline -or -not (Test-Path $EvidenceBaseline)) {
        Copy-EvalArtifact -Source $CurrentReport -Destination $EvidenceBaseline
    }

    if (-not $baselineJson) {
        Write-Host "Stage 8 RAG E2E archived report:"
        Write-Host "  $EvidenceReport"
        return
    }

    $baseline = $baselineJson | ConvertFrom-Json
    $metricKeys = @(
        "passedQueries",
        "recallAt3",
        "recallAt5",
        "recallAt10",
        "hitRateAt3",
        "mrr",
        "ndcgAt3",
        "correctness",
        "faithfulness",
        "latencyP50Ms",
        "latencyP95Ms",
        "latencyP99Ms"
    )

    $lines = @()
    $lines += "# Stage 8 RAG E2E Diff"
    $lines += ""
    $lines += "- baseline: $baselineLabel"
    $lines += "- current: $(Get-RepoRelativePath $CurrentReport)"
    $lines += ""
    $lines += "| Metric | Baseline | Current | Delta |"
    $lines += "| --- | --- | --- | --- |"
    foreach ($key in $metricKeys) {
        $baselineValue = [double]$baseline.summary.$key
        $currentValue = [double]$current.summary.$key
        $delta = [Math]::Round($currentValue - $baselineValue, 4)
        $lines += "| $key | $baselineValue | $currentValue | $delta |"
    }

    Write-Utf8Text -Path $DiffReport -Text ($lines -join [Environment]::NewLine)
    Copy-EvalArtifact -Source $DiffReport -Destination $EvidenceDiff
    Write-Host "Stage 8 RAG E2E archived report:"
    Write-Host "  $EvidenceReport"
}
finally {
    Pop-Location
}
