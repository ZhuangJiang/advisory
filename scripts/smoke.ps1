#requires -Version 5.1
<#
.SYNOPSIS
    End-to-end smoke test for the advisory platform.

.DESCRIPTION
    Walks the whole product path against a running stack:

        login (and the forced password change)
          -> provider + chat/embedding models
          -> knowledge base + node + upload + wait for READY
          -> agent
          -> question over SSE (asserts steps, citations, [n] markers)
          -> GET /api/messages/{id} (the reconnect path)
          -> GET /api/admin/conversations (the audit path)

    The script needs a live OpenAI-compatible or Ollama endpoint for both a chat model and a
    1024-dimension embedding model: there is no stub in the production images. Without them it
    stops with exit code 2 and prints what to set - it never reports SMOKE OK on a partial run.

    The file is intentionally ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI.

.EXAMPLE
    $env:SMOKE_CHAT_BASE_URL  = 'http://127.0.0.1:11434'
    $env:SMOKE_CHAT_MODEL     = 'qwen2.5:7b'
    $env:SMOKE_EMBED_BASE_URL = 'http://127.0.0.1:11434'
    $env:SMOKE_EMBED_MODEL    = 'bge-m3'
    $env:SMOKE_PROVIDER_TYPE  = 'OLLAMA'
    powershell -File scripts/smoke.ps1
#>
[CmdletBinding()]
param(
    # Public entry point (nginx): the SPA and /api.
    [string]$BaseUrl = 'http://localhost',
    # Actuator is not exposed through nginx on purpose; it is reachable on the backend port only.
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$AdminUsername = 'admin',
    [string]$AdminPassword = $(if ($env:SMOKE_ADMIN_PASSWORD) { $env:SMOKE_ADMIN_PASSWORD } else { 'admin' }),
    [string]$AdminNewPassword = 'Admin-Passw0rd!',
    [string]$ProviderType = $env:SMOKE_PROVIDER_TYPE,
    [string]$ChatBaseUrl = $env:SMOKE_CHAT_BASE_URL,
    [string]$ChatApiKey = $env:SMOKE_CHAT_API_KEY,
    [string]$ChatModel = $env:SMOKE_CHAT_MODEL,
    [string]$EmbedBaseUrl = $env:SMOKE_EMBED_BASE_URL,
    [string]$EmbedApiKey = $env:SMOKE_EMBED_API_KEY,
    [string]$EmbedModel = $env:SMOKE_EMBED_MODEL,
    [string]$Question = 'What materials are required to submit an expense claim, and how long do I have?',
    [int]$DocumentTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not $ProviderType) {
    if ($ChatBaseUrl -match '11434') { $ProviderType = 'OLLAMA' } else { $ProviderType = 'OPENAI_COMPATIBLE' }
}
if (-not $EmbedBaseUrl) { $EmbedBaseUrl = $ChatBaseUrl }
if (-not $EmbedApiKey) { $EmbedApiKey = $ChatApiKey }

$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$script:token = $null

function Write-Step([string]$Message) {
    Write-Host ("==> {0}" -f $Message) -ForegroundColor Cyan
}

function Stop-Smoke([string]$Message) {
    Write-Host ("SMOKE FAILED: {0}" -f $Message) -ForegroundColor Red
    exit 1
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        $Body,
        [switch]$Anonymous
    )
    $headers = @{}
    if (-not $Anonymous -and $script:token) {
        $headers['Authorization'] = "Bearer $($script:token)"
    }
    $requestArgs = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        Headers         = $headers
        UseBasicParsing = $true
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $requestArgs['Body'] = ($Body | ConvertTo-Json -Depth 10 -Compress)
        $requestArgs['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $response = Invoke-WebRequest @requestArgs
    } catch {
        $detail = ''
        if ($_.Exception.Response) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $reader.ReadToEnd()
        }
        Stop-Smoke ("{0} {1} failed: {2} {3}" -f $Method, $Path, $_.Exception.Message, $detail)
    }
    $envelope = $response.Content | ConvertFrom-Json
    if ($envelope.code -ne 0) {
        Stop-Smoke ("{0} {1} returned code {2}: {3}" -f $Method, $Path, $envelope.code, $envelope.message)
    }
    return $envelope.data
}

# ---------------------------------------------------------------------------- preconditions

if (-not $ChatBaseUrl -or -not $ChatModel -or -not $EmbedModel) {
    Write-Host 'SMOKE SKIPPED: no model endpoint configured.' -ForegroundColor Yellow
    Write-Host 'Set SMOKE_CHAT_BASE_URL / SMOKE_CHAT_MODEL / SMOKE_EMBED_MODEL (plus the optional'
    Write-Host '*_API_KEY and SMOKE_PROVIDER_TYPE) and run again. A model is required because the'
    Write-Host 'production images contain no stub gateway.'
    exit 2
}

Write-Step "health check $BackendUrl/actuator/health"
try {
    # Invoke-RestMethod parses the actuator JSON; `-UseBasicParsing` returns bytes for this
    # content type, which would never match a string comparison.
    $health = Invoke-RestMethod -Uri "$BackendUrl/actuator/health" -UseBasicParsing
} catch {
    Stop-Smoke "the backend is not reachable at $BackendUrl"
}
if ($health.status -ne 'UP') {
    Stop-Smoke "backend health is $($health.status)"
}

Write-Step "front end $BaseUrl/"
try {
    $index = Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing
    if ($index.Content -notmatch '<div id="app"') { Stop-Smoke 'the SPA index was not served' }
} catch {
    Stop-Smoke "the front end is not reachable at $BaseUrl"
}

# ---------------------------------------------------------------------------- auth

Write-Step "login as $AdminUsername"
$login = Invoke-Api -Method POST -Path '/api/auth/login' -Anonymous -Body @{
    username = $AdminUsername
    password = $AdminPassword
}
$script:token = $login.accessToken

$me = Invoke-Api -Method GET -Path '/api/auth/me'
if ($me.mustChangePassword) {
    Write-Step 'forced password change on first login'
    $changed = Invoke-Api -Method POST -Path '/api/auth/change-password' -Body @{
        oldPassword = $AdminPassword
        newPassword = $AdminNewPassword
    }
    $script:token = $changed.accessToken
    $me = Invoke-Api -Method GET -Path '/api/auth/me'
    if ($me.mustChangePassword) { Stop-Smoke 'the password change did not clear mustChangePassword' }
}

# ---------------------------------------------------------------------------- provider and models

Write-Step 'create provider and models'
$chatProvider = Invoke-Api -Method POST -Path '/api/providers' -Body @{
    name    = "smoke-chat-$stamp"
    type    = $ProviderType
    baseUrl = $ChatBaseUrl
    apiKey  = $ChatApiKey
}
# PowerShell variable names are case-insensitive, so a response variable must not be called
# $chatModel / $embedModel: that is the same variable as the $ChatModel / $EmbedModel parameters.
$chatModelRow = Invoke-Api -Method POST -Path ("/api/providers/{0}/models" -f $chatProvider.id) -Body @{
    modelName  = $ChatModel
    capability = 'CHAT'
}

if ($EmbedBaseUrl -eq $ChatBaseUrl) {
    $embedProvider = $chatProvider
} else {
    $embedProvider = Invoke-Api -Method POST -Path '/api/providers' -Body @{
        name    = "smoke-embed-$stamp"
        type    = $ProviderType
        baseUrl = $EmbedBaseUrl
        apiKey  = $EmbedApiKey
    }
}
$embedModelRow = Invoke-Api -Method POST -Path ("/api/providers/{0}/models" -f $embedProvider.id) -Body @{
    modelName  = $EmbedModel
    capability = 'EMBEDDING'
    dimension  = 1024
}

# ---------------------------------------------------------------------------- knowledge base and document

Write-Step 'create knowledge base, node and upload a sample document'
$kb = Invoke-Api -Method POST -Path '/api/knowledge-bases' -Body @{
    name              = "smoke-kb-$stamp"
    description       = 'created by scripts/smoke.ps1'
    embeddingModelId  = $embedModelRow.id
}
$node = Invoke-Api -Method POST -Path ("/api/knowledge-bases/{0}/nodes" -f $kb.id) -Body @{
    name = 'Expense policy'
}

$sample = @"
Expense policy (smoke sample)

Article 1. An expense claim must include the original invoice, an approval form signed by the
line manager, and an itemised list of expenses.

Article 2. The claim must be submitted within 30 calendar days of the expense date. Claims filed
after that window require a written exception approved by the finance director.

Article 3. Travel expenses follow the same rule, with the addition of a boarding pass or a
hotel invoice when the original receipt was lost.
"@
$bytes = [Text.Encoding]::UTF8.GetBytes($sample)
$filename = "smoke-$stamp.txt"
$presign = Invoke-Api -Method POST -Path '/api/documents/presign' -Body @{
    kbId     = $kb.id
    nodeId   = $node.id
    filename = $filename
    size     = $bytes.Length
}
# The PUT must NOT carry our bearer token: MinIO validates the signature and rejects extra auth.
Invoke-WebRequest -Method PUT -Uri $presign.uploadUrl -Body $bytes -ContentType 'text/plain' -UseBasicParsing | Out-Null
$document = Invoke-Api -Method POST -Path '/api/documents' -Body @{
    kbId      = $kb.id
    nodeId    = $node.id
    filename  = $filename
    objectKey = $presign.objectKey
    size      = $bytes.Length
}

Write-Step "wait for the document to become READY (max ${DocumentTimeoutSeconds}s)"
$deadline = (Get-Date).AddSeconds($DocumentTimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $document = Invoke-Api -Method GET -Path ("/api/documents/{0}" -f $document.id)
    if ($document.status -eq 'FAILED') { Stop-Smoke "document ingestion failed: $($document.errorMsg)" }
} while ($document.status -ne 'READY' -and (Get-Date) -lt $deadline)
if ($document.status -ne 'READY') { Stop-Smoke "document stayed in $($document.status) for ${DocumentTimeoutSeconds}s" }

# The chunk list is a page (`{content,total,...}`), not a bare array: `total` is the honest count.
$chunkPage = Invoke-Api -Method GET -Path ("/api/documents/{0}/chunks?size=1" -f $document.id)
if ($chunkPage.total -lt 1) { Stop-Smoke 'the document produced no chunks' }

# ---------------------------------------------------------------------------- agent and question

Write-Step 'create agent'
$agent = Invoke-Api -Method POST -Path '/api/agents' -Body @{
    name         = "smoke-agent-$stamp"
    description  = 'created by scripts/smoke.ps1'
    systemPrompt = 'Answer only from the provided references and cite them as [n].'
    chatModelId  = $chatModelRow.id
    kbIds        = @($kb.id)
    maxRounds    = 2
    topK         = 20
    temperature  = 0.2
    enabled      = $true
}

Write-Step 'ask one question over SSE'
$conversation = Invoke-Api -Method POST -Path '/api/conversations' -Body @{
    agentId = $agent.id
    title   = "smoke-$stamp"
}

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(4)
$request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post,
    "$BaseUrl/api/conversations/$($conversation.id)/messages")
$request.Headers.Add('Authorization', "Bearer $($script:token)")
$request.Headers.Add('Accept', 'text/event-stream')
$payload = @{ content = $Question } | ConvertTo-Json -Compress
$request.Content = [System.Net.Http.StringContent]::new($payload, [Text.Encoding]::UTF8, 'application/json')

$response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
if (-not $response.IsSuccessStatusCode) {
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Stop-Smoke ("the SSE request was rejected: {0} {1}" -f [int]$response.StatusCode, $body)
}

$stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
$reader = [System.IO.StreamReader]::new($stream)
$messageId = $null
$answer = ''
$stepCount = 0
$citations = @()
$status = $null
$eventName = ''
while (($line = $reader.ReadLine()) -ne $null) {
    if ($line.StartsWith('event:')) {
        $eventName = $line.Substring(6).Trim()
        continue
    }
    if (-not $line.StartsWith('data:')) { continue }
    $json = $line.Substring(5).Trim()
    if (-not $json) { continue }
    $frame = $json | ConvertFrom-Json
    switch ($eventName) {
        'meta' { $messageId = $frame.messageId }
        'step' { $stepCount++ }
        'delta' { $answer += [string]$frame.text }
        'citations' { $citations = @($frame.items) }
        'done' { $status = $frame.status }
        'error' { Stop-Smoke ("the run failed: {0} {1}" -f $frame.code, $frame.message) }
    }
}
$reader.Dispose()
$client.Dispose()

if (-not $messageId) { Stop-Smoke 'no meta frame arrived: the stream never started' }
if ($stepCount -lt 2) { Stop-Smoke "expected at least 2 step frames, got $stepCount" }
if ($citations.Count -lt 1) { Stop-Smoke 'the answer cited no chunk' }
if ($answer -notmatch '\[\d+\]') { Stop-Smoke 'the answer text has no [n] citation marker' }
if ($status -ne 'COMPLETED' -and $status -ne 'TRUNCATED') { Stop-Smoke "unexpected terminal status: $status" }

# ---------------------------------------------------------------------------- persisted record and audit

Write-Step 'read the persisted message back (reconnect path)'
$detail = Invoke-Api -Method GET -Path ("/api/messages/{0}" -f $messageId)
if ($detail.steps.Count -lt 2) { Stop-Smoke "only $($detail.steps.Count) steps were persisted" }
if ($detail.citations.Count -lt 1) { Stop-Smoke 'no citations were persisted' }
if ([string]::IsNullOrWhiteSpace($detail.content)) { Stop-Smoke 'the persisted answer is empty' }

Write-Step 'audit the conversation as an administrator'
$audit = Invoke-Api -Method GET -Path ("/api/admin/conversations?agentId={0}&size=50" -f $agent.id)
$found = @($audit.content | Where-Object { $_.id -eq $conversation.id })
if ($found.Count -ne 1) { Stop-Smoke 'the conversation is missing from the audit list' }
$auditDetail = Invoke-Api -Method GET -Path ("/api/admin/conversations/{0}" -f $conversation.id)
if ($auditDetail.messages.Count -lt 2) { Stop-Smoke 'the audit detail is missing messages' }

Write-Host ''
Write-Host ('SMOKE OK  messageId={0} status={1} steps={2} citations={3} answerChars={4}' -f `
        $messageId, $status, $detail.steps.Count, $detail.citations.Count, $detail.content.Length) -ForegroundColor Green
exit 0
