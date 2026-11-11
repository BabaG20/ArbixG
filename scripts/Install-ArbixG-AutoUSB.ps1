param([switch]$Watch,[int]$PollSeconds=180)
$ErrorActionPreference="Stop"
$Repo="BabaG20/ArbixG"; $Branch="rebuild/okx-triangular-arbitrage-v1"; $Workflow="android-build.yml"
$ArtifactName="ArbixG-debug-apk"; $Root=Join-Path $env:LOCALAPPDATA "ArbixG\AutoUSB"
$InstalledShaFile=Join-Path $Root "installed-commit.txt"; $LogFile=Join-Path $Root "installer.log"; $Worker=Join-Path $Root "AutoInstall.ps1"
New-Item -ItemType Directory -Force -Path $Root | Out-Null
function Log([string]$m){Add-Content -Path $LogFile -Value "[$(Get-Date -Format s)] $m"}
function FindExe([string]$n,[string[]]$paths){$c=Get-Command $n -ErrorAction SilentlyContinue;if($c){return $c.Source};foreach($p in $paths){if($p -and (Test-Path $p)){return $p}};return $null}
function InstallLatest {
 try {
  $gh=FindExe "gh" @("$env:ProgramFiles\GitHub CLI\gh.exe","$env:LOCALAPPDATA\Programs\GitHub CLI\gh.exe")
  $adb=FindExe "adb" @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
  if(-not $gh){Log "GitHub CLI missing.";return}; if(-not $adb){Log "ADB missing.";return}
  & $gh auth status *> $null; if($LASTEXITCODE -ne 0){Log "GitHub CLI not authenticated; run gh auth login.";return}
  $state=& $adb get-state 2>$null
  if($LASTEXITCODE -ne 0 -or "$state".Trim() -ne "device"){Log "No authorized USB device; connect phone and accept USB debugging prompt.";return}
  $json=& $gh run list --repo $Repo --workflow $Workflow --branch $Branch --limit 1 --json databaseId,headSha,status,conclusion
  if($LASTEXITCODE -ne 0){Log "GitHub Actions query failed.";return}
  $runs=@($json | ConvertFrom-Json);if($runs.Count -eq 0){Log "No Android build runs found.";return}
  $run=$runs[0];if($run.status -ne "completed" -or $run.conclusion -ne "success"){Log "Latest build not successful; no install.";return}
  $installed="";if(Test-Path $InstalledShaFile){$installed=(Get-Content $InstalledShaFile -Raw).Trim()}
  if($installed -eq $run.headSha){return}
  $temp=Join-Path $env:TEMP ("ArbixG-"+$run.headSha.Substring(0,8));if(Test-Path $temp){Remove-Item $temp -Recurse -Force}
  New-Item -ItemType Directory -Force -Path $temp | Out-Null
  & $gh run download $run.databaseId --repo $Repo --name $ArtifactName --dir $temp
  if($LASTEXITCODE -ne 0){Log "Artifact download failed.";return}
  $apk=Get-ChildItem $temp -Filter "*.apk" -Recurse | Select-Object -First 1
  if(-not $apk){Log "No APK found in artifact.";return}
  Log "Installing successful build for commit $($run.headSha)."
  & $adb install -r $apk.FullName
  if($LASTEXITCODE -ne 0){Log "ADB install failed; will retry.";return}
  Set-Content -Path $InstalledShaFile -Value $run.headSha
  Log "Install succeeded. App data retained by adb install -r."
  Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
 } catch {Log "ERROR: $($_.Exception.Message)"}
}
if(-not $Watch){
 $source=$MyInvocation.MyCommand.Path;if(-not $source -or -not(Test-Path $source)){throw "Run from downloaded script file."}
 Copy-Item $source $Worker -Force
 $gh=FindExe "gh" @("$env:ProgramFiles\GitHub CLI\gh.exe","$env:LOCALAPPDATA\Programs\GitHub CLI\gh.exe")
 $adb=FindExe "adb" @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
 if(-not $gh){throw "Install GitHub CLI from https://cli.github.com/"};if(-not $adb){throw "ADB not found; install Android SDK Platform-Tools."}
 & $gh auth status;if($LASTEXITCODE -ne 0){throw "Run gh auth login first."}
 $args='-NoProfile -ExecutionPolicy Bypass -File "'+$Worker+'" -Watch -PollSeconds '+$PollSeconds
 $action=New-ScheduledTaskAction -Execute "powershell.exe" -Argument $args
 $trigger=New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
 $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)
 Register-ScheduledTask -TaskName "ArbixG Auto USB Installer" -Action $action -Trigger $trigger -Settings $settings -Description "Auto installs successful ArbixG Android builds over USB." -Force | Out-Null
 Start-ScheduledTask -TaskName "ArbixG Auto USB Installer"
 Log "Scheduled task installed; polling every $PollSeconds seconds."
 Write-Host "ArbixG Auto USB Installer installed. Keep USB debugging enabled." -ForegroundColor Green
 Write-Host "Log: $LogFile";exit 0
}
Log "Watcher started."
while($true){InstallLatest;Start-Sleep -Seconds ([Math]::Max(60,$PollSeconds))}
