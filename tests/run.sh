#!/usr/bin/env bash
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
: "${JSON_JAR:?Set JSON_JAR to an org.json runtime jar}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
sources=("$project/src/app/lastfuck/ZipRunner.java" "$project/tests/ZipRunnerRegression.java" "$project"/tests/stubs/lastfuck/*.java "$project/src/app/lastfuck/CodingAgent.java" "$project/src/app/lastfuck/AutonomyPolicy.java" "$project/tests/AutonomyRegression.java" "$project/src/app/lastfuck/WorkspaceFiles.java" "$project/src/app/lastfuck/RuntimeFiles.java" "$project/src/app/lastfuck/TaskerTasks.java" "$project/src/app/lastfuck/ConversationLog.java" "$project/src/app/lastfuck/WorkspaceBrowser.java" "$project/src/app/lastfuck/ActionReview.java" "$project/tests/ComputerRegression.java" "$project/tests/AgentRegression.java" "$project/src/app/lastfuck/TaskerBridge.java" "$project/tests/TaskerBridgeRegression.java" "$project"/tests/android/content/*.java "$project"/tests/android/content/pm/*.java "$project"/tests/android/net/*.java "$project/src/app/lastfuck/KleinModel.java" "$project/tests/KleinParity.java")
if [[ -n "${ECJ_JAR:-}" ]]; then
    java -jar "$ECJ_JAR" -17 -proc:none -warn:none -cp "$JSON_JAR" -d "$work" "${sources[@]}"
else
    javac -cp "$JSON_JAR" -d "$work" "${sources[@]}"
fi
java -cp "$work:$JSON_JAR" app.lastfuck.AgentRegression
java -cp "$work:$JSON_JAR" app.lastfuck.AutonomyRegression

java -cp "$work:$JSON_JAR" app.lastfuck.TaskerBridgeRegression

java -cp "$work:$JSON_JAR" app.lastfuck.ComputerRegression

java -cp "$work:$JSON_JAR" app.lastfuck.KleinParity "$project/assets/klein/klein-weights.bin" "$project/tests/klein_parity.json"

java -cp "$work:$JSON_JAR" app.lastfuck.ZipRunnerRegression
