param(
    [Parameter(Mandatory = $true)]
    [string]$SuiteId,
    [Parameter(Mandatory = $true)]
    [string]$Capability,
    [Parameter(Mandatory = $true)]
    [string]$SampleSetName,
    [string]$SuiteType = "custom",
    [string]$Owner = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$EvidenceRoot = Join-Path $RepoRoot "docs\evidence\agent-quantification"
$SuiteRoot = Join-Path $EvidenceRoot $SuiteId
$NewLine = [Environment]::NewLine
$CreatedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [string]$Content = ""
    )

    $directory = Split-Path -Parent $Path
    if ($directory) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }

    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.Encoding]::UTF8)
}

$readmeLines = @(
    "# $SuiteId",
    "",
    "## Suite Info",
    "",
    "- suiteId: $SuiteId",
    "- suiteType: $SuiteType",
    "- capability: $Capability",
    "- sampleSetName: $SampleSetName",
    "- owner: $Owner",
    "- createdAt: $CreatedAt",
    "",
    "## Directory Layout",
    "",
    "- sample-set.md: fixed sample list and verifier rules",
    "- raw-results.md: case-level raw results",
    "- summary.md: summary metrics and resume-safe conclusion",
    "- baselines/: before-change snapshots",
    "- reports/: JSON / Markdown / diff reports",
    "- traces/: representative traces, screenshots, or API outputs",
    "",
    "## Report Naming",
    "",
    "- reports/$SuiteId-report.json",
    "- reports/$SuiteId-report.md",
    "- reports/$SuiteId-diff.md",
    "- baselines/$SuiteId-baseline-YYYY-MM-DD.json"
)

$sampleSetLines = @(
    "# $SampleSetName",
    "",
    "## Sample Info",
    "",
    "- suiteId: $SuiteId",
    "- capability: $Capability",
    "- suiteType: $SuiteType",
    "",
    "## Fixed Cases",
    "",
    "| caseId | scenarioType | intent | setup | verifier | notes |",
    "| --- | --- | --- | --- | --- | --- |",
    "|  |  |  |  |  |  |",
    "",
    "## Control Variables",
    "",
    "- model:",
    "- runtimeConfig:",
    "- approvalMode:",
    "- baselineReference:"
)

$rawResultLines = @(
    "# $SuiteId Raw Results",
    "",
    "## Run Info",
    "",
    "- executedAt:",
    "- runner:",
    "- reportPath:",
    "",
    "## Case Results",
    "",
    "| caseId | expected | actual | passed | latencyMs | notes |",
    "| --- | --- | --- | --- | --- | --- |",
    "|  |  |  |  |  |  |"
)

$summaryLines = @(
    "# $SuiteId Summary",
    "",
    "## Core Metrics",
    "",
    "- totalCases:",
    "- passedCases:",
    "- keyMetric1:",
    "- keyMetric2:",
    "- keyMetric3:",
    "",
    "## Resume Gate",
    "",
    "- fixedSampleSet:",
    "- unifiedMetricDefinition:",
    "- rawRecords:",
    "- reviewableEvidence:",
    "- resumeSafe:",
    "",
    "## Notes",
    "",
    "- risks:",
    "- followUp:"
)

New-Item -ItemType Directory -Force -Path (Join-Path $SuiteRoot "baselines") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $SuiteRoot "reports") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $SuiteRoot "traces") | Out-Null

Write-Utf8File -Path (Join-Path $SuiteRoot "README.md") -Content ($readmeLines -join $NewLine)
Write-Utf8File -Path (Join-Path $SuiteRoot "sample-set.md") -Content ($sampleSetLines -join $NewLine)
Write-Utf8File -Path (Join-Path $SuiteRoot "raw-results.md") -Content ($rawResultLines -join $NewLine)
Write-Utf8File -Path (Join-Path $SuiteRoot "summary.md") -Content ($summaryLines -join $NewLine)

Write-Host "Initialized suite:"
Write-Host "  $SuiteRoot"
