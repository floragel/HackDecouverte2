#!/bin/sh
# Generates Config/Secrets.xcconfig from ../local.properties and the Xcode project.
cd "$(dirname "$0")"
P=../local.properties
if [ -f "$P" ]; then
  URL=$(grep '^supabase.url=' "$P" | cut -d= -f2- | sed 's#https://#https:/$()/#')
  KEY=$(grep '^supabase.anonKey=' "$P" | cut -d= -f2-)
  DEMO=$(grep '^lync.demo=' "$P" | cut -d= -f2- | tr '[:lower:]' '[:upper:]')
  [ "$DEMO" = "TRUE" ] && DEMO=YES || DEMO=NO
  printf 'SUPABASE_URL = %s\nSUPABASE_KEY = %s\nLYNC_DEMO = %s\n' "$URL" "$KEY" "$DEMO" > Config/Secrets.xcconfig
  echo "Config/Secrets.xcconfig written"
fi
command -v xcodegen >/dev/null || { echo "Install XcodeGen: brew install xcodegen"; exit 1; }
xcodegen generate
