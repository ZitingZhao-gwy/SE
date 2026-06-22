param(
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "MutsumiLZZ520!",
    [switch]$SkipFrontend,
    [switch]$KeepTradingData
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Logs = Join-Path $Root ".run-logs"
$RedisDir = Join-Path $Root "redis"
$KafkaDir = Join-Path $Root "kafka_2.13-3.6.1"
$JavaExe = "C:\Program Files\Java\jdk-25.0.2\bin\java.exe"
$MysqlExe = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$RedisExe = Join-Path $RedisDir "redis-server.exe"
$RedisCli = Join-Path $RedisDir "redis-cli.exe"
$RedisConf = Join-Path $Root "online-info-publish\redis.windows.conf"
$KafkaServerProps = Join-Path $KafkaDir "config\kraft\server.properties"
$KafkaLibs = Join-Path $KafkaDir "libs\*"

New-Item -ItemType Directory -Force -Path $Logs | Out-Null

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Test-PortListening {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-PortListening {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -Port $Port) {
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "Timed out waiting for port $Port"
}

function Stop-PortProcess {
    param([int]$Port)

    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $connections) {
        return
    }

    $pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        try {
            Stop-Process -Id $procId -Force -ErrorAction Stop
        } catch {
        }
    }
}

function Invoke-Mysql {
    param([string]$Query)

    & $MysqlExe -u $MysqlUser "--password=$MysqlPassword" --default-character-set=utf8mb4 -e $Query
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed."
    }
}

function Invoke-MysqlFile {
    param(
        [string]$Database,
        [string]$SqlFile
    )

    & $MysqlExe -u $MysqlUser "--password=$MysqlPassword" --default-character-set=utf8mb4 $Database -e "source $SqlFile"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to execute SQL file: $SqlFile"
    }
}

function Ensure-BaseDatabases {
    Write-Step "Initializing MySQL databases"

    Invoke-Mysql "CREATE DATABASE IF NOT EXISTS central_trading DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
    Invoke-Mysql "CREATE DATABASE IF NOT EXISTS trading_client DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
    Invoke-Mysql "CREATE DATABASE IF NOT EXISTS stock_publish DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
    Invoke-Mysql "CREATE DATABASE IF NOT EXISTS stock_trade_management DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
    Invoke-Mysql "CREATE DATABASE IF NOT EXISTS account_db DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci;"

    Invoke-MysqlFile -Database "account_db" -SqlFile (Join-Path $Root "account-management\scripts\mysql_schema_current.sql")
    Invoke-MysqlFile -Database "account_db" -SqlFile (Join-Path $Root "account-management\scripts\mysql_seed_smoke.sql")
    Invoke-MysqlFile -Database "stock_trade_management" -SqlFile (Join-Path $Root "trade-management\sql\schema.sql")
    Invoke-MysqlFile -Database "stock_trade_management" -SqlFile (Join-Path $Root "trade-management\sql\seed.sql")
    Invoke-MysqlFile -Database "trading_client" -SqlFile (Join-Path $Root "trading-client\database\schema.sql")

    Invoke-Mysql @"
USE stock_publish;
CREATE TABLE IF NOT EXISTS local_user_subscription (
    id INT AUTO_INCREMENT PRIMARY KEY,
    global_user_id VARCHAR(50) NOT NULL UNIQUE,
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    upgrade_time DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sync_stock_info (
    stock_code CHAR(6) PRIMARY KEY,
    stock_name VARCHAR(100) NOT NULL,
    stock_type INT NOT NULL,
    yesterday_close DECIMAL(10,2) NOT NULL,
    limit_rate DECIMAL(5,4) NOT NULL,
    status INT NOT NULL,
    pinyin_abbr VARCHAR(20) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS kline_5m_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code CHAR(6) NOT NULL,
    period_start_time DATETIME NOT NULL,
    open_price DECIMAL(10,2) NOT NULL,
    close_price DECIMAL(10,2) NOT NULL,
    high_price DECIMAL(10,2) NOT NULL,
    low_price DECIMAL(10,2) NOT NULL,
    volume BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_code_time (stock_code, period_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

    if (-not $KeepTradingData) {
        Invoke-Mysql "TRUNCATE TABLE central_trading.trade_record; TRUNCATE TABLE central_trading.order_book;"
    }
}

function Start-Redis {
    Write-Step "Starting Redis"

    if (-not (Test-PortListening -Port 6379)) {
        Start-Process -FilePath $RedisExe `
            -ArgumentList @($RedisConf) `
            -WorkingDirectory $RedisDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $Logs "redis.out.log") `
            -RedirectStandardError (Join-Path $Logs "redis.err.log") | Out-Null
    }

    Wait-PortListening -Port 6379 -TimeoutSeconds 20
    & $RedisCli ping | Out-Null
}

function Start-Kafka {
    Write-Step "Starting Kafka"

    if (-not (Test-PortListening -Port 9092)) {
        Start-Process -FilePath $JavaExe `
            -ArgumentList @("-cp", $KafkaLibs, "kafka.Kafka", $KafkaServerProps) `
            -WorkingDirectory $KafkaDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $Logs "kafka.out.log") `
            -RedirectStandardError (Join-Path $Logs "kafka.err.log") | Out-Null
    }

    Wait-PortListening -Port 9092 -TimeoutSeconds 40
}

function Start-JavaService {
    param(
        [string]$Name,
        [string]$JarPath,
        [int]$Port,
        [hashtable]$EnvVars
    )

    Write-Step "Starting $Name"
    if (Test-PortListening -Port $Port) {
        return
    }

    $envAssignments = ($EnvVars.GetEnumerator() | ForEach-Object { "`$env:$($_.Key) = '$($_.Value)'" }) -join "; "
    $command = if ([string]::IsNullOrWhiteSpace($envAssignments)) {
        "& '$JavaExe' -jar '$JarPath'"
    } else {
        "$envAssignments; & '$JavaExe' -jar '$JarPath'"
    }

    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-Command", $command) `
        -WorkingDirectory (Split-Path $JarPath -Parent) `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Logs "$Name.out.log") `
        -RedirectStandardError (Join-Path $Logs "$Name.err.log") | Out-Null

    Wait-PortListening -Port $Port -TimeoutSeconds 90
}

function Start-NodeService {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Command,
        [int]$Port,
        [hashtable]$EnvVars
    )

    Write-Step "Starting $Name"
    if (Test-PortListening -Port $Port) {
        return
    }

    $envAssignments = ($EnvVars.GetEnumerator() | ForEach-Object { "`$env:$($_.Key) = '$($_.Value)'" }) -join "; "
    $fullCommand = if ([string]::IsNullOrWhiteSpace($envAssignments)) {
        $Command
    } else {
        "$envAssignments; $Command"
    }

    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-Command", $fullCommand) `
        -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Logs "$Name.out.log") `
        -RedirectStandardError (Join-Path $Logs "$Name.err.log") | Out-Null

    Wait-PortListening -Port $Port -TimeoutSeconds 90
}

function Test-HttpJson {
    param(
        [string]$Url,
        [hashtable]$Headers
    )

    try {
        if ($Headers) {
            return Invoke-RestMethod -Uri $Url -Headers $Headers -TimeoutSec 8
        }
        return Invoke-RestMethod -Uri $Url -TimeoutSec 8
    } catch {
        return $null
    }
}

Write-Step "Stopping old processes on target ports"
foreach ($port in 6379, 8080, 8081, 8082, 8083, 8090, 9092, 3000, 5173) {
    Stop-PortProcess -Port $port
}
Start-Sleep -Seconds 3

Ensure-BaseDatabases
Start-Redis
Start-Kafka

Start-JavaService -Name "account-management" `
    -JarPath (Join-Path $Root "account-management\target\account-management-1.0.0-SNAPSHOT.jar") `
    -Port 8080 `
    -EnvVars @{
        ACCOUNT_DB_USERNAME = $MysqlUser
        ACCOUNT_DB_PASSWORD = $MysqlPassword
        ACCOUNT_BLACKLIST_BASE_URL = "http://localhost:8081"
    }

Start-JavaService -Name "trade-management" `
    -JarPath (Join-Path $Root "trade-management\target\stock-trade-management-1.0.0.jar") `
    -Port 8081 `
    -EnvVars @{}

Start-JavaService -Name "central-trading" `
    -JarPath (Join-Path $Root "central-trading\target\central-trading-1.0.0-SNAPSHOT.jar") `
    -Port 8082 `
    -EnvVars @{
        DB_HOST = "localhost"
        DB_PORT = "3306"
        DB_DATABASE = "central_trading"
        DB_USER = $MysqlUser
        DB_PASSWORD = $MysqlPassword
        KAFKA_ENABLED = "true"
        KAFKA_BROKERS = "localhost:9092"
        ACCOUNT_API_BASE = "http://localhost:8080"
        ACCOUNT_API_MOCK = "false"
        CALL_AUCTION_HOUR = "0"
        CALL_AUCTION_MINUTE = "0"
        SPRING_SQL_INIT_MODE = "never"
    }

Start-JavaService -Name "online-publish" `
    -JarPath (Join-Path $Root "online-info-publish\target\online-info-publish-subsys-1.0.0-SNAPSHOT.jar") `
    -Port 8083 `
    -EnvVars @{
        SPRING_DATASOURCE_USERNAME = $MysqlUser
        SPRING_DATASOURCE_PASSWORD = $MysqlPassword
    }

Start-NodeService -Name "trading-client" `
    -WorkingDirectory (Join-Path $Root "trading-client") `
    -Command "node server/app.js" `
    -Port 8090 `
    -EnvVars @{
        DB_HOST = "127.0.0.1"
        DB_PORT = "3306"
        DB_USER = $MysqlUser
        DB_PASSWORD = $MysqlPassword
        DB_NAME = "trading_client"
        KAFKA_ENABLED = "true"
        KAFKA_BROKERS = "localhost:9092"
        KAFKAJS_NO_PARTITIONER_WARNING = "1"
        ACCOUNT_BACKEND = "http://localhost:8080"
        CENTRAL_BACKEND = "http://localhost:8082"
        MGMT_BACKEND = "http://localhost:8081"
    }

if (-not $SkipFrontend) {
    Start-NodeService -Name "account-frontend" `
        -WorkingDirectory (Join-Path $Root "account-management\frontend") `
        -Command "npm run dev -- --host 0.0.0.0" `
        -Port 5173 `
        -EnvVars @{}

    Start-NodeService -Name "publish-frontend" `
        -WorkingDirectory (Join-Path $Root "online-info-publish\publish-frontend") `
        -Command "npm run dev -- --host 0.0.0.0" `
        -Port 3000 `
        -EnvVars @{}
}

Write-Step "Health check"
$results = [ordered]@{
    redis = Test-PortListening -Port 6379
    kafka = Test-PortListening -Port 9092
    account = [bool](Test-HttpJson -Url "http://localhost:8080/api/external/fund/snapshot?fund_acc_no=FA20260620212321&auth_token=test")
    management = [bool](Test-HttpJson -Url "http://localhost:8081/")
    central = [bool](Test-HttpJson -Url "http://localhost:8082/api/central-trading/admin/kafka/status")
    publish = [bool](Test-HttpJson -Url "http://localhost:8083/api/publish/stock/search?keyword=600519")
    client = [bool](Test-HttpJson -Url "http://localhost:8090/api/client/central/kafka/status")
}

$results.GetEnumerator() | ForEach-Object {
    Write-Host ("{0,-12} {1}" -f $_.Key, $(if ($_.Value) { "OK" } else { "FAIL" }))
}

Write-Host ""
Write-Host "Logs: $Logs" -ForegroundColor Green
