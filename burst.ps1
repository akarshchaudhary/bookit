param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminToken = $(if ($env:ADMIN_TOKEN) { $env:ADMIN_TOKEN } else { "admin-secret-token" }),
    [string]$HotSeat = "A12",
    [int]$Concurrency = 200,
    [int]$Users = 200
)

$ErrorActionPreference = "Stop"
Write-Host "==> Burst against $BaseUrl"

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-RestMethod -Uri "$BaseUrl/readyz" -Method Get | Out-Null
        $ready = $true
        break
    } catch {
        Start-Sleep -Seconds 1
    }
}
if (-not $ready) { throw "Service never became ready" }

$seats = @("A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13","A14","A15","A16","A17","A18","A19","A20")
$showBody = @{ name = "burst-$(Get-Date -Format yyyyMMddHHmmss)"; seats = $seats; price_paise = 25000; per_user_limit = 4 } | ConvertTo-Json
$show = Invoke-RestMethod -Uri "$BaseUrl/shows" -Method Post -Headers @{ "X-Admin-Token" = $AdminToken; "Content-Type" = "application/json" } -Body $showBody
$showId = $show.id
Write-Host "==> Created show $showId"
Write-Host "==> Hot-seat storm: $Concurrency requests for seat $HotSeat"

$confirmed = [ref]0
$declined = [ref]0
$server = [ref]0
$lock = New-Object object

$jobs = 1..$Concurrency | ForEach-Object {
    $n = $_
    Start-Job -ScriptBlock {
        param($BaseUrl, $ShowId, $HotSeat, $Users, $N)
        $userNum = (($N - 1) % $Users) + 1
        $token = "user-token-{0:D3}" -f $userNum
        $key = "burst-$N-$(Get-Random)"
        $body = @{ seats = @($HotSeat); idempotency_key = $key } | ConvertTo-Json
        try {
            $resp = Invoke-WebRequest -Uri "$BaseUrl/shows/$ShowId/reserve" -Method Post `
                -Headers @{ Authorization = "Bearer $token"; "Content-Type" = "application/json"; "X-Request-Id" = "burst-$N" } `
                -Body $body -UseBasicParsing
            return [int]$resp.StatusCode
        } catch {
            $ex = $_.Exception
            if ($ex.Response -ne $null) {
                try { return [int]$ex.Response.StatusCode } catch { return 500 }
            }
            return 500
        }
    } -ArgumentList $BaseUrl, $showId, $HotSeat, $Users, $n
}

foreach ($job in $jobs) {
    $code = Receive-Job -Job $job -Wait
    Remove-Job $job
    if ($code -eq 201) { $confirmed.Value++ }
    elseif ($code -ge 500) { $server.Value++ }
    else { $declined.Value++ }
}

$state = Invoke-RestMethod -Uri "$BaseUrl/shows/$showId"
$sum = $state.available + $state.held + $state.confirmed

Write-Host ""
Write-Host "=== Outcome distribution (hot-seat storm) ==="
Write-Host "confirmed(201): $($confirmed.Value)"
Write-Host "declined(4xx):  $($declined.Value)"
Write-Host "server(5xx):    $($server.Value)"
Write-Host ""
Write-Host "=== Reconciliation ==="
Write-Host "available=$($state.available) held=$($state.held) confirmed=$($state.confirmed) total=$($state.total_seats) sum=$sum"

if ($sum -eq $state.total_seats -and $confirmed.Value -eq 1 -and $server.Value -eq 0) {
    Write-Host "PASS: invariant holds, exactly one hot-seat winner, zero 5xx"
    exit 0
}
Write-Host "FAIL: check distribution / invariant"
exit 1
