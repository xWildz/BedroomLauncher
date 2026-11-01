# Finds the Philips Google TV on the network by its Chromecast name (it has no fixed IP).
# Prints the IP address. Usage:  powershell -ExecutionPolicy Bypass -File tools\find-tv.ps1

param([string]$Name = "PHILIPS 4K TV")

$myIp = @((Get-NetIPConfiguration | Where-Object IPv4DefaultGateway).IPv4Address.IPAddress)[0]
$net = $myIp -replace '\.\d+$', ''

$probes = foreach ($i in 1..254) {
    $c = New-Object Net.Sockets.TcpClient
    [pscustomobject]@{ Ip = "$net.$i"; Client = $c; Task = $c.ConnectAsync("$net.$i", 8008) }
}
Start-Sleep -Milliseconds 1500

$found = $null
foreach ($p in $probes) {
    if (-not $found -and $p.Task.IsCompleted -and -not $p.Task.IsFaulted -and $p.Client.Connected) {
        try {
            $xml = [xml](Invoke-WebRequest "http://$($p.Ip):8008/ssdp/device-desc.xml" -UseBasicParsing -TimeoutSec 3).Content
            if ($xml.root.device.friendlyName -eq $Name) { $found = $p.Ip }
        } catch {}
    }
    $p.Client.Close()
}

if ($found) { $found } else { Write-Error "Couldn't find '$Name'. Is the TV on?"; exit 1 }
