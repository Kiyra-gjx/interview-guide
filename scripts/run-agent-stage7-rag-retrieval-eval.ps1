param(
    [string]$BaselineReport,
    [switch]$UpdateBaseline
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleUserHome = Join-Path $RepoRoot ".gradle-cache"
$ReportDir = Join-Path $RepoRoot "app\build\reports\agent-eval"
$CurrentReport = Join-Path $ReportDir "stage-7-rag-retrieval-set-report.json"
$CurrentMarkdown = Join-Path $ReportDir "stage-7-rag-retrieval-set-report.md"
$DiffReport = Join-Path $ReportDir "stage-7-rag-retrieval-set-diff.md"
$EvidenceRoot = Join-Path $RepoRoot "docs\evidence\agent-quantification\stage-7-rag-retrieval-set"
$EvidenceReportDir = Join-Path $EvidenceRoot "reports"
$EvidenceBaselineDir = Join-Path $EvidenceRoot "baselines"
$EvidenceReport = Join-Path $EvidenceReportDir "stage-7-rag-retrieval-set-report.json"
$EvidenceMarkdown = Join-Path $EvidenceReportDir "stage-7-rag-retrieval-set-report.md"
$EvidenceDiff = Join-Path $EvidenceReportDir "stage-7-rag-retrieval-set-diff.md"
$EvidenceBaseline = Join-Path $EvidenceBaselineDir "stage-7-rag-retrieval-set-baseline-2026-05-08.json"

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

function Get-RepoRelativePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

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
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force $parent | Out-Null
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.Encoding]::UTF8)
}

function Copy-EvalArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Source,
        [Parameter(Mandatory = $true)]
        [string]$Destination
    )

    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force $parent | Out-Null
    Copy-Item -Path $Source -Destination $Destination -Force
}

function Format-MarkdownCell {
    param($Value)

    if ($null -eq $Value) {
        return ""
    }
    return ([string]$Value -replace '\|', '\|' -replace '\r?\n', ' ').Trim()
}

function New-MarkdownRow {
    param(
        [object[]]$Cells
    )

    $escapedCells = @()
    foreach ($cell in $Cells) {
        $escapedCells += Format-MarkdownCell $cell
    }
    return "| $($escapedCells -join ' | ') |"
}

function New-MarkdownSeparator {
    param(
        [Parameter(Mandatory = $true)]
        [int]$ColumnCount
    )

    $cells = @()
    for ($i = 0; $i -lt $ColumnCount; $i++) {
        $cells += "---"
    }
    return "| $($cells -join ' | ') |"
}

function Get-RunnerInvocation {
    $parts = @(
        "powershell",
        "-ExecutionPolicy Bypass",
        "-File scripts/run-agent-stage7-rag-retrieval-eval.ps1"
    )
    if ($BaselineReport) {
        $parts += "-BaselineReport $(Get-RepoRelativePath $BaselineReport)"
    }
    if ($UpdateBaseline) {
        $parts += "-UpdateBaseline"
    }
    return ($parts -join " ")
}

function Get-CaseCandidateSummary {
    param($Case)

    if (-not $Case -or -not $Case.candidates) {
        return "MISSING"
    }
    $parts = @()
    foreach ($candidate in $Case.candidates) {
        $parts += "$($candidate.rawHitCount):$($candidate.effectiveHit):$($candidate.rejectionReason)"
    }
    if ($parts.Count -eq 0) {
        return "EMPTY"
    }
    return ($parts -join ";")
}

function Get-CaseCandidateEvidenceSummary {
    param($Case)

    if (-not $Case -or -not $Case.candidates) {
        return "MISSING"
    }
    $parts = @()
    foreach ($candidate in $Case.candidates) {
        $candidateQuery = if ($null -eq $candidate.query) { "n/a" } else { ([string]$candidate.query -replace '\s+', ' ').Trim() }
        $candidateHits = Get-CaseHitSummary $candidate
        $parts += "query=$candidateQuery;rawHitCount=$($candidate.rawHitCount);effectiveHit=$($candidate.effectiveHit);rejectionReason=$($candidate.rejectionReason);hits=$candidateHits"
    }
    if ($parts.Count -eq 0) {
        return "EMPTY"
    }
    return ($parts -join ";")
}

function Get-CaseHitSummary {
    param($Case)

    if ($null -eq $Case -or $null -eq $Case.hits) {
        return "MISSING"
    }
    if ($Case.hits.Count -eq 0) {
        return "EMPTY"
    }

    $parts = @()
    foreach ($hit in $Case.hits) {
        $preview = if ($null -eq $hit.preview) { "" } else { ([string]$hit.preview -replace '\s+', ' ').Trim() }
        $parts += "$($hit.sourceTitle)#$($hit.chunkIndex):$($hit.sectionTitle):$preview"
    }
    return ($parts -join ";")
}

function Get-CaseEvidenceSummary {
    param($Case)

    if ($null -eq $Case) {
        return "MISSING"
    }

    $answerPreview = if ($null -eq $Case.answerPreview) { "n/a" } else { ([string]$Case.answerPreview -replace '\s+', ' ').Trim() }
    $note = if ($null -eq $Case.note) { "n/a" } else { ([string]$Case.note -replace '\s+', ' ').Trim() }
    return "hits=$(Get-CaseHitSummary $Case); answerPreview=$answerPreview; note=$note"
}

function Get-CaseLatencyMs {
    param($Case)

    if ($null -eq $Case -or $null -eq $Case.latencyMs) {
        return "MISSING"
    }
    return $Case.latencyMs
}

function Get-RawCandidateHitCount {
    param($Case)

    if (-not $Case -or -not $Case.candidates) {
        return "MISSING"
    }
    $total = 0
    foreach ($candidate in $Case.candidates) {
        $total += [int]$candidate.rawHitCount
    }
    return $total
}

function Get-RejectionReasons {
    param($Case)

    if (-not $Case -or -not $Case.candidates) {
        return "MISSING"
    }
    $reasons = @()
    foreach ($candidate in $Case.candidates) {
        if ($candidate.rejectionReason) {
            $reasons += $candidate.rejectionReason
        }
    }
    if ($reasons.Count -eq 0) {
        return "n/a"
    }
    return (($reasons | Sort-Object -Unique) -join "; ")
}

function Write-RawResults {
    param(
        $Report,
        [Parameter(Mandatory = $true)]
        [string]$RunnerInvocation,
        [string]$DiffBaselineLabel
    )

    $rawResultsPath = Join-Path $EvidenceRoot "raw-results.md"
    $lines = @()
    $lines += "# stage-7-rag-retrieval-set Raw Results"
    $lines += ""
    $lines += "## Run Info"
    $lines += ""
    $lines += "- executedAt: ``$($Report.generatedAt)``"
    $lines += "- runner: ``$RunnerInvocation``"
    $lines += "- reportPath: ``reports/stage-7-rag-retrieval-set-report.json``"
    $lines += "- diffPath: ``reports/stage-7-rag-retrieval-set-diff.md``"
    $lines += "- archivedBaselinePath: ``baselines/stage-7-rag-retrieval-set-baseline-2026-05-08.json``"
    if ($DiffBaselineLabel) {
        $lines += "- diffBaseline: ``$DiffBaselineLabel``"
    }
    $lines += ""
    $lines += "## Case Results"
    $lines += ""
    $rawHeaders = @("caseId", "queryType", "top1Hit", "top3Hit", "answerGrounded", "noAnswerRejected", "hallucinated", "hitCount", "rawCandidateHits", "rejectionReason", "passed")
    $lines += New-MarkdownRow -Cells $rawHeaders
    $lines += New-MarkdownSeparator -ColumnCount $rawHeaders.Count
    foreach ($case in $Report.caseResults) {
        $lines += New-MarkdownRow -Cells @(
            $case.caseId,
            $case.queryType,
            $case.top1Hit,
            $case.top3Hit,
            $case.answerGrounded,
            $case.noAnswerRejected,
            $case.hallucinated,
            $case.hitCount,
            (Get-RawCandidateHitCount $case),
            (Get-RejectionReasons $case),
            $case.passed
        )
    }
    $lines += ""
    $lines += "## Notes"
    $lines += ""
    $lines += "- ``latencyMs`` is local test runtime noise and is not resume-safe."
    $lines += "- ``passed=true`` means the fixed expected source, section, evidence, and no-answer assertions matched."
    Write-Utf8Text -Path $rawResultsPath -Text ($lines -join [Environment]::NewLine)
}

function Write-Summary {
    param($Report)

    $summaryPath = Join-Path $EvidenceRoot "summary.md"
    $lines = @()
    $lines += "# stage-7-rag-retrieval-set Summary"
    $lines += ""
    $lines += "## Core Metrics"
    $lines += ""
    $lines += "- totalQueries: ``$($Report.summary.totalQueries)``"
    $lines += "- passedQueries: ``$($Report.summary.passedQueries)``"
    $lines += "- answerableQueries: ``$($Report.summary.answerableQueries)``"
    $lines += "- noAnswerQueries: ``$($Report.summary.noAnswerQueries)``"
    $lines += "- weakHitNoAnswerQueries: ``1``"
    $lines += "- top1HitRate: ``$($Report.summary.top1HitRate)%``"
    $lines += "- top3HitRate: ``$($Report.summary.top3HitRate)%``"
    $lines += "- answerGroundedRate: ``$($Report.summary.answerGroundedRate)%``"
    $lines += "- noAnswerRejectionRate: ``$($Report.summary.noAnswerRejectionRate)%``"
    $lines += "- hallucinationCount: ``$($Report.summary.hallucinationCount)``"
    $lines += ""
    $lines += "## Resume Gate"
    $lines += ""
    $lines += "- fixedSampleSet: ``yes``"
    $lines += "- unifiedMetricDefinition: ``yes``"
    $lines += "- rawRecords: ``yes``"
    $lines += "- reviewableEvidence: ``yes``"
    $lines += "- resumeSafe: ``partial``"
    $lines += ""
    $lines += "## Resume-Safe Claims"
    $lines += ""
    $lines += "- Built a fixed Stage 7 RAG retrieval eval set with ``20`` queries covering source/section hits, precision-term retrieval, grounded answer checks, empty-hit rejection, and weak-hit no-answer rejection."
    $lines += "- Added report metrics for ``top1HitRate``, ``top3HitRate``, ``answerGroundedRate``, ``noAnswerRejectionRate``, and ``hallucinationCount``, with case-level debug evidence."
    $lines += "- Anchored retrieval assertions to chunk evidence fields: ``sourceTitle``, ``sectionTitle``, ``chunkIndex``, and ``preview``."
    $lines += ""
    $lines += "## Do Not Claim"
    $lines += ""
    $lines += "- Do not claim production RAG accuracy from this suite."
    $lines += "- Do not claim public benchmark results."
    $lines += "- Do not use local test latency as a performance result."
    $lines += ""
    $lines += "## Follow-Up"
    $lines += ""
    $lines += "- S7-03 should add external content injection cases."
    $lines += "- S7-04 should add tool routing contract cases."
    Write-Utf8Text -Path $summaryPath -Text ($lines -join [Environment]::NewLine)
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

    & .\gradlew.bat :app:agentStage7RagRetrievalEval --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not (Test-Path $CurrentReport)) {
        throw "Current report not found: $CurrentReport"
    }

    if (-not (Test-Path $CurrentMarkdown)) {
        throw "Current markdown report not found: $CurrentMarkdown"
    }

    Write-Host "Stage 7 RAG retrieval report:"
    Write-Host "  $CurrentMarkdown"
    Copy-EvalArtifact -Source $CurrentReport -Destination $EvidenceReport
    Copy-EvalArtifact -Source $CurrentMarkdown -Destination $EvidenceMarkdown
    $current = (Read-Utf8Text $CurrentReport) | ConvertFrom-Json
    Write-RawResults -Report $current -RunnerInvocation (Get-RunnerInvocation) -DiffBaselineLabel $baselineLabel
    Write-Summary $current

    if ($UpdateBaseline -or -not (Test-Path $EvidenceBaseline)) {
        Copy-EvalArtifact -Source $CurrentReport -Destination $EvidenceBaseline
    }
    Write-Host "Stage 7 RAG retrieval archived report:"
    Write-Host "  $EvidenceReport"
    Write-Host "Stage 7 RAG retrieval archived baseline:"
    Write-Host "  $EvidenceBaseline"

    if (-not $BaselineReport) {
        return
    }

    $baseline = $baselineJson | ConvertFrom-Json

    $metricKeys = @(
        "passedQueries",
        "answerableQueries",
        "noAnswerQueries",
        "top1HitRate",
        "top3HitRate",
        "answerGroundedRate",
        "noAnswerRejectionRate",
        "hallucinationCount",
        "averageLatencyMs",
        "maxLatencyMs"
    )

    $lines = @()
    $lines += "# Stage 7 RAG Retrieval Set Diff"
    $lines += ""
    $lines += "- baseline: $baselineLabel"
    $lines += "- current: $(Get-RepoRelativePath $CurrentReport)"
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
    $caseHeaders = @(
        "Case",
        "Baseline Passed",
        "Current Passed",
        "Baseline Top1",
        "Current Top1",
        "Baseline Top3",
        "Current Top3",
        "Baseline Grounded",
        "Current Grounded",
        "Baseline NoAnswerRejected",
        "Current NoAnswerRejected",
        "Baseline Hallucinated",
        "Current Hallucinated",
        "Baseline EffectiveHit",
        "Current EffectiveHit",
        "Baseline RetrievalQuery",
        "Current RetrievalQuery",
        "Baseline HitCount",
        "Current HitCount",
        "Baseline RawHits",
        "Current RawHits",
        "Baseline Rejection",
        "Current Rejection",
        "Baseline CandidateSummary",
        "Current CandidateSummary",
        "Baseline CandidateEvidenceSummary",
        "Current CandidateEvidenceSummary",
        "Baseline EvidenceSummary",
        "Current EvidenceSummary",
        "Baseline LatencyMs",
        "Current LatencyMs",
        "Changed"
    )
    $lines += New-MarkdownRow -Cells $caseHeaders
    $lines += New-MarkdownSeparator -ColumnCount $caseHeaders.Count

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
        $changed = (
            (Get-CaseField $baselineCase "passed") -ne (Get-CaseField $currentCase "passed") `
            -or (Get-CaseField $baselineCase "top1Hit") -ne (Get-CaseField $currentCase "top1Hit") `
            -or (Get-CaseField $baselineCase "top3Hit") -ne (Get-CaseField $currentCase "top3Hit") `
            -or (Get-CaseField $baselineCase "answerGrounded") -ne (Get-CaseField $currentCase "answerGrounded") `
            -or (Get-CaseField $baselineCase "noAnswerRejected") -ne (Get-CaseField $currentCase "noAnswerRejected") `
            -or (Get-CaseField $baselineCase "hallucinated") -ne (Get-CaseField $currentCase "hallucinated") `
            -or (Get-CaseField $baselineCase "effectiveHit") -ne (Get-CaseField $currentCase "effectiveHit") `
            -or (Get-CaseField $baselineCase "retrievalQuery") -ne (Get-CaseField $currentCase "retrievalQuery") `
            -or (Get-CaseField $baselineCase "hitCount") -ne (Get-CaseField $currentCase "hitCount") `
            -or (Get-CaseCandidateSummary $baselineCase) -ne (Get-CaseCandidateSummary $currentCase) `
            -or (Get-CaseCandidateEvidenceSummary $baselineCase) -ne (Get-CaseCandidateEvidenceSummary $currentCase) `
            -or (Get-CaseEvidenceSummary $baselineCase) -ne (Get-CaseEvidenceSummary $currentCase)
        )

        # Latency is shown for diagnosis only; it is runtime noise and not part of Changed.
        $lines += New-MarkdownRow -Cells @(
            $caseId,
            (Get-CaseField $baselineCase "passed"),
            (Get-CaseField $currentCase "passed"),
            (Get-CaseField $baselineCase "top1Hit"),
            (Get-CaseField $currentCase "top1Hit"),
            (Get-CaseField $baselineCase "top3Hit"),
            (Get-CaseField $currentCase "top3Hit"),
            (Get-CaseField $baselineCase "answerGrounded"),
            (Get-CaseField $currentCase "answerGrounded"),
            (Get-CaseField $baselineCase "noAnswerRejected"),
            (Get-CaseField $currentCase "noAnswerRejected"),
            (Get-CaseField $baselineCase "hallucinated"),
            (Get-CaseField $currentCase "hallucinated"),
            (Get-CaseField $baselineCase "effectiveHit"),
            (Get-CaseField $currentCase "effectiveHit"),
            (Get-CaseField $baselineCase "retrievalQuery"),
            (Get-CaseField $currentCase "retrievalQuery"),
            (Get-CaseField $baselineCase "hitCount"),
            (Get-CaseField $currentCase "hitCount"),
            (Get-RawCandidateHitCount $baselineCase),
            (Get-RawCandidateHitCount $currentCase),
            (Get-RejectionReasons $baselineCase),
            (Get-RejectionReasons $currentCase),
            (Get-CaseCandidateSummary $baselineCase),
            (Get-CaseCandidateSummary $currentCase),
            (Get-CaseCandidateEvidenceSummary $baselineCase),
            (Get-CaseCandidateEvidenceSummary $currentCase),
            (Get-CaseEvidenceSummary $baselineCase),
            (Get-CaseEvidenceSummary $currentCase),
            (Get-CaseLatencyMs $baselineCase),
            (Get-CaseLatencyMs $currentCase),
            $changed
        )
    }

    Write-Utf8Text -Path $DiffReport -Text ($lines -join [Environment]::NewLine)
    Copy-EvalArtifact -Source $DiffReport -Destination $EvidenceDiff
    Write-Host "Stage 7 RAG retrieval diff report:"
    Write-Host "  $DiffReport"
    Write-Host "Stage 7 RAG retrieval archived diff report:"
    Write-Host "  $EvidenceDiff"
}
finally {
    Pop-Location
}
