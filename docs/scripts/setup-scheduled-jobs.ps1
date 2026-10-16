<#
.SYNOPSIS
  One-time setup for issue #26: Cloud Scheduler calls POST /internal/jobs/daily on the Cloud Run service every
  night, so the GDPR retention purge runs even when the service is scaled to zero.

.DESCRIPTION
  Run as a project owner in Windows PowerShell, AFTER the PR that adds /internal/jobs/daily is deployed.
  Every step is safe to re-run: what already exists is skipped (or updated, for the Scheduler job).

    1. Enable the Cloud Scheduler API.
    2. Create the service account qr-attendance-scheduler. It gets NO roles: the Cloud Run service is public, so
       it needs no run.invoker; it only exists so Google signs an OIDC token whose email claim names it.
    3. Point the app at it (JOBS_TRIGGER_SERVICE_ACCOUNT, JOBS_TRIGGER_AUDIENCE) and turn off the in-app timers
       (JOBS_IN_APP_SCHEDULE=false). This deploys a new revision of the current image with the same secrets.
    4. Create (or update) the Scheduler job in europe-west1: 03:30 Europe/Bucharest daily, POST with a Google OIDC
       token for audience = PUBLIC_BASE_URL + /internal/jobs/daily.
    5. Run it once and show the purge log line.

  Nothing here is a secret. Uses gcloud.cmd from PowerShell (Git Bash mangles gcloud.cmd arguments with spaces).
  Arguments avoid embedded double quotes, which Windows PowerShell 5.1 doesn't escape for native commands.
#>
# Not 'Stop': in Windows PowerShell 5.1 that turns any stderr line of a native command (gcloud prints progress
# there) into a terminating error. Failures are caught by exit code in Invoke-GCloud instead.
$ErrorActionPreference = 'Continue'

$gcloud    = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$Project   = 'qr-attendance-sanducc'
$Region    = 'europe-west1'
$Service   = 'qr-attendance'
$JobName   = 'qr-attendance-daily-jobs'
$SaName    = 'qr-attendance-scheduler'
$SchedulerSa = "$SaName@$Project.iam.gserviceaccount.com"

function Invoke-GCloud {
    & $gcloud @args
    if ($LASTEXITCODE -ne 0) { throw "gcloud $($args -join ' ') failed with exit code $LASTEXITCODE" }
}

# The endpoint URL (= the token audience) is built from PUBLIC_BASE_URL on the service, today the run.app URL.
$svc = (& $gcloud run services describe $Service --project $Project --region $Region --format=json) | ConvertFrom-Json
$baseUrl = ($svc.spec.template.spec.containers[0].env | Where-Object { $_.name -eq 'PUBLIC_BASE_URL' }).value
if (-not $baseUrl) { throw 'PUBLIC_BASE_URL is not set on the service' }
$Uri = $baseUrl.TrimEnd('/') + '/internal/jobs/daily'
Write-Host "Endpoint and token audience: $Uri"

# 1. Cloud Scheduler API
Invoke-GCloud services enable cloudscheduler.googleapis.com --project $Project

# 2. Scheduler identity, no roles
& $gcloud iam service-accounts describe $SchedulerSa --project $Project --format='value(email)' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Invoke-GCloud iam service-accounts create $SaName --project $Project `
        --display-name 'qr-attendance Cloud Scheduler (OIDC caller of /internal/jobs/daily, no roles)'
}

# 3. The app accepts only this account's tokens, for exactly this audience; the in-app timers are off on Cloud Run.
#    The audience is pinned (not derived from PUBLIC_BASE_URL), so a later custom domain (#31) doesn't break it.
Invoke-GCloud run services update $Service --project $Project --region $Region `
    --update-env-vars "JOBS_TRIGGER_SERVICE_ACCOUNT=$SchedulerSa,JOBS_TRIGGER_AUDIENCE=$Uri,JOBS_IN_APP_SCHEDULE=false"

# 4. The nightly job. Retries: the endpoint is idempotent, and a cold start takes ~20 s of the 180 s deadline.
$jobArgs = @(
    '--project', $Project, '--location', $Region,
    '--schedule', '30 3 * * *', '--time-zone', 'Europe/Bucharest',
    '--uri', $Uri, '--http-method', 'POST',
    '--oidc-service-account-email', $SchedulerSa, '--oidc-token-audience', $Uri,
    '--attempt-deadline', '180s',
    '--max-retry-attempts', '3', '--min-backoff', '10m', '--max-backoff', '1h',
    '--description', 'GDPR retention purge and auto-close (issue 26)'
)
& $gcloud scheduler jobs describe $JobName --project $Project --location $Region --format='value(name)' 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    Invoke-GCloud scheduler jobs update http $JobName @jobArgs
} else {
    Invoke-GCloud scheduler jobs create http $JobName @jobArgs
}

# 5. Prove it: an anonymous call is refused, the scheduler's call runs the purge
try {
    Invoke-WebRequest -Method Post -Uri $Uri -UseBasicParsing | Out-Null
    Write-Warning 'An anonymous POST was NOT rejected. Check the deployed revision.'
} catch {
    Write-Host "Anonymous POST rejected as expected: $($_.Exception.Response.StatusCode.value__)"
}
Invoke-GCloud scheduler jobs run $JobName --project $Project --location $Region
Write-Host 'Waiting 60 s for the run (includes a possible cold start)...'
Start-Sleep -Seconds 60
Invoke-GCloud scheduler jobs describe $JobName --project $Project --location $Region `
    --format='table(state,lastAttemptTime,status.code,scheduleTime)'
Invoke-GCloud logging read `
    "resource.type=cloud_run_revision AND resource.labels.service_name=$Service AND textPayload:RetentionJob" `
    --project $Project --freshness 10m --limit 3 --format='value(timestamp,textPayload)'
