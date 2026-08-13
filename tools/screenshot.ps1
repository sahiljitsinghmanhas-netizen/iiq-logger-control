<#
  Capture real screenshots of the plugin page by driving headless Chrome over
  the DevTools Protocol.

  No Selenium, Playwright or Node: Chrome ships the protocol, and .NET has a
  WebSocket client, so the only dependency is Chrome itself.

  Authentication is the awkward part - the plugin page is a JSF page behind a
  form login, not Basic auth. So we log in with Invoke-WebRequest first, take
  the JSESSIONID off the session, and inject it into the browser with
  Network.setCookie before navigating.

  Usage:
    powershell -ExecutionPolicy Bypass -File tools\screenshot.ps1 `
        -BaseUrl http://localhost:8080/identityiq -User spadmin -Password admin `
        -OutDir docs\screenshots
#>
param(
    [string]$BaseUrl  = "http://localhost:8080/identityiq",
    [string]$User     = "spadmin",
    [string]$Password = "admin",
    [string]$OutDir   = "docs\screenshots",
    [int]$Width       = 1600,
    [int]$Height      = 1200
)

$ErrorActionPreference = "Stop"

function Find-Chrome {
    $candidates = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    throw "No Chrome or Edge found."
}

# ---- 1. log in the ordinary way and keep the session cookie ---------------
function Get-SessionCookie {
    param($BaseUrl, $User, $Password)

    $session = $null
    $login = Invoke-WebRequest -Uri "$BaseUrl/login.jsf" -SessionVariable session -UseBasicParsing
    $vs = ([regex]'name="javax\.faces\.ViewState"[^>]*value="([^"]+)"').Match($login.Content).Groups[1].Value

    $body = @{
        "loginForm"                    = "loginForm"
        "loginForm:accountId"          = $User
        "loginForm:password"           = $Password
        "loginForm:initialTimeZoneId"  = [System.TimeZoneInfo]::Local.Id
        "loginForm:preLoginUrl"        = ""
        "loginForm:loginButton"        = "Login"
        "javax.faces.ViewState"        = $vs
    }
    Invoke-WebRequest -Uri "$BaseUrl/login.jsf" -Method Post -Body $body -WebSession $session -UseBasicParsing | Out-Null

    # Every cookie, not just JSESSIONID: IIQ also issues XSRF-TOKEN, and the
    # page's REST calls are rejected without it - which renders as "session
    # expired" even though the page shell itself loaded logged in.
    $uri = [Uri]$BaseUrl
    $jar = @()
    foreach ($c in $session.Cookies.GetCookies($uri)) {
        $jar += @{ name = $c.Name; value = $c.Value; domain = $uri.Host; path = "/" }
    }
    if (-not ($jar | Where-Object { $_.name -eq "JSESSIONID" })) {
        throw "Login did not yield a JSESSIONID - check the credentials."
    }
    return $jar
}

# ---- 2. a very small CDP client ------------------------------------------
$script:cdpId = 0
function Invoke-Cdp {
    param($Socket, [string]$Method, $Params = @{}, [int]$TimeoutSec = 30)

    $script:cdpId++
    $msg = @{ id = $script:cdpId; method = $Method; params = $Params } | ConvertTo-Json -Depth 12 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
    $seg = New-Object System.ArraySegment[byte] (,$bytes)
    $Socket.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true,
                      [System.Threading.CancellationToken]::None).Wait()

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $sb = New-Object System.Text.StringBuilder
        do {
            $buf = New-Object byte[] 65536
            $rseg = New-Object System.ArraySegment[byte] (,$buf)
            $task = $Socket.ReceiveAsync($rseg, [System.Threading.CancellationToken]::None)
            $task.Wait()
            $res = $task.Result
            [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
        } while (-not $res.EndOfMessage)

        $obj = $sb.ToString() | ConvertFrom-Json
        # events stream in alongside replies; keep reading until ours arrives
        if ($obj.PSObject.Properties.Name -contains 'id' -and $obj.id -eq $script:cdpId) {
            if ($obj.PSObject.Properties.Name -contains 'error') {
                throw "CDP $Method failed: $($obj.error.message)"
            }
            return $obj.result
        }
    }
    throw "CDP $Method timed out."
}

# ---- 3. run ---------------------------------------------------------------
$chrome = Find-Chrome
Write-Host "browser : $chrome"

$jar = Get-SessionCookie -BaseUrl $BaseUrl -User $User -Password $Password
Write-Host ("session : {0} cookie(s) - {1}" -f $jar.Count, (($jar | ForEach-Object { $_.name }) -join ", "))

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$profile = Join-Path $env:TEMP ("tol-shot-" + [Guid]::NewGuid().ToString("N"))
$port = 9333

$args = @(
    "--headless=new", "--disable-gpu", "--hide-scrollbars",
    "--remote-debugging-port=$port", "--user-data-dir=$profile",
    "--window-size=$Width,$Height", "--force-device-scale-factor=1",
    "--no-first-run", "--no-default-browser-check", "about:blank"
)
$proc = Start-Process -FilePath $chrome -ArgumentList $args -PassThru -WindowStyle Hidden
Write-Host "chrome  : pid $($proc.Id), debugging on $port"

try {
    $target = $null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $list = Invoke-RestMethod "http://127.0.0.1:$port/json/list"
            $target = $list | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
            if ($target) { break }
        } catch { }
    }
    if (-not $target) { throw "Chrome did not expose a page target." }

    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ws.ConnectAsync([Uri]$target.webSocketDebuggerUrl, [System.Threading.CancellationToken]::None).Wait()
    Write-Host "cdp     : connected"

    $uri = [Uri]$BaseUrl
    Invoke-Cdp $ws "Network.enable" @{} | Out-Null
    foreach ($c in $jar) {
        Invoke-Cdp $ws "Network.setCookie" $c | Out-Null
    }
    Invoke-Cdp $ws "Page.enable" @{} | Out-Null

    $shots = @(
        @{ file = "logger-manager.png"; url = "$BaseUrl/plugins/pluginPage.jsf?pn=TurnOnLoggers"; wait = 6 },
        @{ file = "help.png";           url = "$BaseUrl/plugin/TurnOnLoggers/ui/help.html";       wait = 2 },
        @{ file = "configure.png";      url = "$BaseUrl/plugins/pluginConfig.jsf#/configuration?pn=TurnOnLoggers"; wait = 5 }
    )

    foreach ($s in $shots) {
        Invoke-Cdp $ws "Page.navigate" @{ url = $s.url } | Out-Null
        Start-Sleep -Seconds $s.wait     # the page fetches /state and renders client-side
        $metrics = Invoke-Cdp $ws "Page.getLayoutMetrics" @{}
        $h = [int][Math]::Min($metrics.cssContentSize.height, 4000)
        Invoke-Cdp $ws "Emulation.setDeviceMetricsOverride" @{
            width = $Width; height = $h; deviceScaleFactor = 1; mobile = $false
        } | Out-Null
        $shot = Invoke-Cdp $ws "Page.captureScreenshot" @{ format = "png"; captureBeyondViewport = $true }
        $out = Join-Path $OutDir $s.file
        [IO.File]::WriteAllBytes($out, [Convert]::FromBase64String($shot.data))
        $kb = [Math]::Round((Get-Item $out).Length / 1KB)
        Write-Host ("shot    : {0}  {1}x{2}  {3} KB" -f $s.file, $Width, $h, $kb)
    }

    $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done",
                   [System.Threading.CancellationToken]::None).Wait()
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
    Remove-Item -Recurse -Force $profile -ErrorAction SilentlyContinue
}
Write-Host "done    : $OutDir"
