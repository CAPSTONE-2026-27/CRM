@echo off
rem Weekly marketing retraining, run by Windows Task Scheduler.
rem Needs the user environment variable CRM_FEEDBACK_DB_URL (the CRM database's
rem libpq connection string). Output is appended to ai\logs\retrain.log.
cd /d "%~dp0.."
if not exist logs mkdir logs
echo. >> logs\retrain.log
echo ===== %date% %time% ===== >> logs\retrain.log
"venv\Scripts\python.exe" tools\retrain.py >> logs\retrain.log 2>&1
