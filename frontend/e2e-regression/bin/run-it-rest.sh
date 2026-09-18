#!/bin/bash
# 续跑 L5~L8（L1~L4 已于 21:11~21:13 全绿）
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
LOGDIR="$ROOT/frontend/e2e-regression/.runs/it-logs"
SUM="$ROOT/frontend/e2e-regression/.runs/it-summary.txt"
run() {
  line="$1"; classes="$2"
  echo "== $line start $(date +%H:%M:%S)" >> "$SUM"
  cd "$ROOT/backend" || exit 1
  mvn -o test -pl notification4j-it -Dtest="$classes" > "$LOGDIR/$line.log" 2>&1
  rc=$?
  totals=$(grep -E "Tests run: .* Failures" "$LOGDIR/$line.log" | tail -1)
  echo "== $line RC=$rc $totals" >> "$SUM"
  cd "$ROOT" || exit 1
}
run L5 "NfyDeliveryEngineTest,NfyDeliveryPlanTest,NfyQuietHoursTest"
run L6 "NfyPlatformTenantTest,NfyPlatformDomainTest,NfyTenantComplianceTest,NfySignatureKeyTest"
run L7 "NfyTemplateTest,NfyOpsHealthTest,NfySchemaMigrationTest"
run L8 "NfyStaticPageTest,NfySmokeTest"
echo "ALL_DONE $(date +%H:%M:%S)" >> "$SUM"
