# VoidClear 自检：编译 + 逐个跑离线自检，任何一项失败就非零退出。
# 用法（仓库根目录）：  powershell -ExecutionPolicy Bypass -File .\check.ps1
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
Set-Location -Path $PSScriptRoot

function Fail($message) {
    Write-Host "FAIL: $message" -ForegroundColor Red
    exit 1
}

# 1. 依赖 classpath（离线优先，避免没网时卡住）
$cpFile = Join-Path $PSScriptRoot 'target\check-classpath.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $cpFile) | Out-Null
mvn -o -q dependency:build-classpath "-Dmdep.outputFile=$cpFile"
if ($LASTEXITCODE -ne 0) { Fail "mvn dependency:build-classpath 失败 (exit $LASTEXITCODE)" }

# 2. 编译主代码与测试代码
mvn -o -q test-compile
if ($LASTEXITCODE -ne 0) { Fail "mvn test-compile 失败 (exit $LASTEXITCODE)" }

$cp = (Get-Content $cpFile -Raw).Trim() + ';target\classes;target\test-classes'

# 3. 逐个自检：必须开 -ea，否则 assert 全是空跑
$checks = @(
    @{ Name = 'PlaceholdersCheck'; Main = 'net.voidclear.PlaceholdersCheck' },
    @{ Name = 'TrashSqliteCheck'; Main = 'net.voidclear.v2.store.TrashSqliteCheck' },
    @{ Name = 'TrashStoreCheck'; Main = 'net.voidclear.v2.store.TrashStoreCheck' },
    @{ Name = 'DatabaseTasksCheck'; Main = 'net.voidclear.v2.store.DatabaseTasksCheck' },
    @{ Name = 'PlayerAuditLogCheck'; Main = 'net.voidclear.v2.store.PlayerAuditLogCheck' },
    @{ Name = 'DiagnosticsCheck'; Main = 'net.voidclear.v2.store.DiagnosticsCheck' },
    @{ Name = 'TrashAccessCheck'; Main = 'net.voidclear.v2.listener.TrashAccessCheck' },
    @{ Name = 'ImportantDropCheck'; Main = 'net.voidclear.v2.rule.ImportantDropCheck' },
    @{ Name = 'SlicedSweepCheck'; Main = 'net.voidclear.v2.runtime.SlicedSweepCheck' },
    @{ Name = 'GuiSettingsCheck'; Main = 'net.voidclear.v2.config.GuiSettingsCheck' },
    @{ Name = 'SweepBudgetCheck'; Main = 'net.voidclear.v2.runtime.SweepBudgetCheck' },
    @{ Name = 'SweepControlCheck'; Main = 'net.voidclear.v2.runtime.SweepControlCheck' },
    @{ Name = 'TrashFilterCheck'; Main = 'net.voidclear.v2.listener.TrashFilterCheck' },
    @{ Name = 'FilteredWithdrawalCheck'; Main = 'net.voidclear.v2.store.FilteredWithdrawalCheck' },
    @{ Name = 'TransferSlotsCheck'; Main = 'net.voidclear.v2.listener.TransferSlotsCheck' },
    @{ Name = 'DeliveryFailureLogCheck'; Main = 'net.voidclear.v2.store.DeliveryFailureLogCheck' },
    @{ Name = 'PlayerSaveReceiptCheck'; Main = 'net.voidclear.v2.store.PlayerSaveReceiptCheck' },
    @{ Name = 'ReceiptCleanupCheck'; Main = 'net.voidclear.v2.store.ReceiptCleanupCheck' },
    @{ Name = 'PlayerDataDirectoryCheck'; Main = 'net.voidclear.v2.store.PlayerDataDirectoryCheck' },
    @{ Name = 'PlayerJournalCheck'; Main = 'net.voidclear.v2.store.PlayerJournalCheck' },
    @{ Name = 'ItemFenceCheck'; Main = 'net.voidclear.v2.store.ItemFenceCheck' },
    @{ Name = 'PlayerSavedRecoveryCheck'; Main = 'net.voidclear.v2.store.PlayerSavedRecoveryCheck' },
    @{ Name = 'PlayerSessionCheck'; Main = 'net.voidclear.v2.store.PlayerSessionCheck' },
    @{ Name = 'QuitReceiptCheck'; Main = 'net.voidclear.v2.listener.QuitReceiptCheck' },
    @{ Name = 'LoginReservationCheck'; Main = 'net.voidclear.v2.listener.LoginReservationCheck' },
    @{ Name = 'CommandCheck'; Main = 'net.voidclear.CommandCheck' },
    @{ Name = 'CommandHelpCheck'; Main = 'net.voidclear.CommandHelpCheck' },
    @{ Name = 'LifecycleCheck'; Main = 'net.voidclear.LifecycleCheck' },
    @{ Name = 'ChatNoticeCheck'; Main = 'net.voidclear.v2.runtime.ChatNoticeCheck' },
    @{ Name = 'DecideCheck'; Main = 'net.voidclear.v2.rule.DecideCheck' },
    @{ Name = 'MigrationCheck'; Main = 'net.voidclear.v2.config.MigrationCheck' },
    @{ Name = 'CountdownCheck'; Main = 'net.voidclear.v2.runtime.CountdownCheck' },
    @{ Name = 'SchedulerCheck'; Main = 'net.voidclear.v2.runtime.SchedulerCheck' },
    @{ Name = 'SweepPreviewCheck'; Main = 'net.voidclear.v2.runtime.SweepPreviewCheck' },
    @{ Name = 'DropProtectionCheck'; Main = 'net.voidclear.v2.runtime.DropProtectionCheck' },
    @{ Name = 'SweeperCheck'; Main = 'net.voidclear.v2.runtime.SweeperCheck' },
    @{ Name = 'SweepReportCheck'; Main = 'net.voidclear.v2.runtime.SweepReportCheck' },
    @{ Name = 'HistoryLogCheck'; Main = 'net.voidclear.v2.store.HistoryLogCheck' }
)
foreach ($check in $checks) {
    Write-Host ("== {0}" -f $check.Name)
    java -ea -cp $cp $check.Main
    if ($LASTEXITCODE -ne 0) { Fail ("{0} 未通过 (exit {1})" -f $check.Name, $LASTEXITCODE) }
}

# SelfCheck 与 ConfigCheck 是仓库根目录下直接编译的单文件自检
$out = Join-Path $PSScriptRoot 'target\check-out'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot 'src\main\java') | ForEach-Object FullName)
$sources += (Join-Path $PSScriptRoot 'SelfCheck.java')
$sources += (Join-Path $PSScriptRoot 'ConfigCheck.java')
javac --release 21 -encoding UTF-8 -d $out -cp (Get-Content $cpFile -Raw).Trim() $sources
if ($LASTEXITCODE -ne 0) { Fail "javac（主代码 + SelfCheck）失败 (exit $LASTEXITCODE)" }

$rootCp = (Get-Content $cpFile -Raw).Trim() + ';' + $out
Write-Host '== SelfCheck'
java -ea -cp $rootCp SelfCheck
if ($LASTEXITCODE -ne 0) { Fail "SelfCheck 未通过 (exit $LASTEXITCODE)" }

Write-Host '== ConfigCheck'
java -ea -cp $rootCp ConfigCheck
if ($LASTEXITCODE -ne 0) { Fail "ConfigCheck 未通过 (exit $LASTEXITCODE)" }

Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green
exit 0
