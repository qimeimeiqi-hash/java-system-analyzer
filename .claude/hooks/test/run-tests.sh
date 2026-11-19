#!/usr/bin/env bash
# Tests for ArchitectureCheck.java (WBS No.13). Run from anywhere: bash .claude/hooks/test/run-tests.sh
#
# 1. Rule fixtures: each file under fixtures/src/main/java declares "// expect: R1:4 ..." (rule:line)
#    or "// expect: none" on its first line; the --files report must match exactly.
# 2. Hook modes: hook JSON on stdin -> expected exit code (and rule ID on stderr).
set -u

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
HOOKS_DIR="$(dirname "$TEST_DIR")"
PROJECT_DIR="$(cd "$HOOKS_DIR/../.." && pwd)"
CHECK="${ARCHITECTURE_CHECK:-$HOOKS_DIR/ArchitectureCheck.java}"
FIXTURES="$TEST_DIR/fixtures"
MAIN_FIXTURES="$FIXTURES/src/main/java/com/example/javaanalyzer"

passed=0
failed=0

pass() { passed=$((passed + 1)); echo "PASS $1"; }
fail() { failed=$((failed + 1)); echo "FAIL $1"; echo "     $2"; }

# Windows path with JSON-escaped backslashes (Claude Code sends absolute Windows paths).
json_path() {
  local path="$1"
  if command -v cygpath > /dev/null; then path="$(cygpath -w "$path")"; fi
  printf '%s' "$path" | sed 's/\\/\\\\/g'
}

# ---- 1. rule fixtures ----
while IFS= read -r fixture; do
  name="${fixture#"$MAIN_FIXTURES"/}"
  expected="$(head -1 "$fixture" | sed -n 's|^// expect: *||p' | tr ' ' '\n' | grep -v '^none$' | grep -v '^$' | sort | tr '\n' ' ')"
  report="$(java "$CHECK" --files "$fixture" 2>&1)"
  code=$?
  actual="$(printf '%s\n' "$report" | sed -n 's|^  \([0-9]*\)行目 \[\(R[0-9]\)\].*|\2:\1|p' | sort | tr '\n' ' ')"
  expected_code=0
  if [ -n "$expected" ]; then expected_code=1; fi
  if [ "$actual" = "$expected" ] && [ "$code" -eq "$expected_code" ]; then
    pass "rules $name -> ${expected:-none}"
  else
    fail "rules $name" "expected [${expected}] exit $expected_code, got [${actual}] exit $code: $report"
  fi
done < <(find "$MAIN_FIXTURES" -name '*.java' | sort)

# ---- 2. hook modes ----
# run_hook <label> <mode> <project dir> <json> <expected exit> [expected text on stderr]
run_hook() {
  local label="$1" mode="$2" project="$3" json="$4" expected_code="$5" expected_text="${6:-}"
  local stderr code
  stderr="$(printf '%s' "$json" | CLAUDE_PROJECT_DIR="$project" java "$CHECK" "$mode" 2>&1 > /dev/null)"
  code=$?
  if [ "$code" -ne "$expected_code" ]; then
    fail "hook $label" "expected exit $expected_code, got $code: $stderr"
  elif [ -n "$expected_text" ] && ! printf '%s' "$stderr" | grep -qF "$expected_text"; then
    fail "hook $label" "stderr does not contain '$expected_text': $stderr"
  else
    pass "hook $label"
  fi
}

violating="$(json_path "$MAIN_FIXTURES/generation/ImportsAnalysis.java")"
allowed="$(json_path "$MAIN_FIXTURES/generation/Allowed.java")"
test_side="$(json_path "$FIXTURES/src/test/java/com/example/javaanalyzer/generation/TestSideImportsAnalysis.java")"
claude_md="$(json_path "$PROJECT_DIR/CLAUDE.md")"

run_hook "post-edit: Edit of a violating file is reported (exit 2)" --post-edit "$PROJECT_DIR" \
  "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$violating\"}}" 2 "[R1]"
run_hook "post-edit: Write of a compliant file passes" --post-edit "$PROJECT_DIR" \
  "{\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$allowed\"}}" 0
run_hook "post-edit: test sources are ignored" --post-edit "$PROJECT_DIR" \
  "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$test_side\"}}" 0
run_hook "post-edit: non-Java files are ignored" --post-edit "$PROJECT_DIR" \
  "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$claude_md\"}}" 0
run_hook "post-edit: missing file is ignored" --post-edit "$PROJECT_DIR" \
  "{\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"src/main/java/Missing.java\"}}" 0
run_hook "post-edit: Serena relative_path is resolved against the project" --post-edit "$FIXTURES" \
  '{"tool_name":"mcp__serena__replace_symbol_body","tool_input":{"relative_path":"src/main/java/com/example/javaanalyzer/ir/FrameworkTypes.java"}}' 2 "[R3]"
run_hook "post-edit: Serena rename_symbol checks the whole tree" --post-edit "$FIXTURES" \
  '{"tool_name":"mcp__serena__rename_symbol","tool_input":{"relative_path":"src/main/java/com/example/javaanalyzer/web/Allowed.java"}}' 2 "[R4]"
run_hook "post-edit: multi-file tool on the real project passes" --post-edit "$PROJECT_DIR" \
  '{"tool_name":"mcp__serena__replace_in_files","tool_input":{}}' 0
run_hook "post-edit: invalid JSON never blocks" --post-edit "$PROJECT_DIR" 'not json' 0
run_hook "pre-commit: git commit with violations is blocked" --pre-commit "$FIXTURES" \
  '{"tool_name":"Bash","tool_input":{"command":"git add -A && git commit -m \"x\""}}' 2 "コミットを中止"
run_hook "pre-commit: PowerShell git commit with violations is blocked" --pre-commit "$FIXTURES" \
  '{"tool_name":"PowerShell","tool_input":{"command":"git add -A; git commit -m @'"'"'\nmsg\n'"'"'@"}}' 2 "[R2]"
run_hook "pre-commit: other git commands are not checked" --pre-commit "$FIXTURES" \
  '{"tool_name":"Bash","tool_input":{"command":"git status"}}' 0
run_hook "pre-commit: git commit on the real project passes" --pre-commit "$PROJECT_DIR" \
  '{"tool_name":"Bash","tool_input":{"command":"git commit -m \"x\""}}' 0

echo
echo "passed: $passed, failed: $failed"
[ "$failed" -eq 0 ]
