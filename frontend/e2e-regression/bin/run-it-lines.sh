#!/bin/bash
# 逐业务线既有 IT 深回归（串行，防 m2 竞态）。日志: frontend/e2e-regression/.runs/it-logs/Lx.log（gitignored）
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
LOGDIR="$ROOT/frontend/e2e-regression/.runs/it-logs"
SUM="$ROOT/frontend/e2e-regression/.runs/it-summary.txt"
mkdir -p "$LOGDIR"
: > "$SUM"

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

run L1 "NfyAuthFlowTest,NfyRegistrationKeyFlowTest,NfyReviewGapTest,NfyClientStarterTest,NfyRemoteClientUnitTest,NfySignatureFaceTest"
run L2 "NfyMessageFlowTest,NfyBatchSendJobTest,NfyMessageCancelTest,NfyQueryCompletionTest,NfyNotifyClientTest"
run L3 "NfyAnnouncementFlowTest,NfyAnnouncementAdminTest"
run L4 "NfyChannelFlowTest,NfyChannelAdminTest,NfySubscriptionFlowTest"
run L5 "NfyDeliveryEngineTest,NfyDeliveryPlanTest,NfyQuietHoursTest"
run L6 "NfyPlatformTenantTest,NfyPlatformDomainTest,NfyTenantComplianceTest,NfySignatureKeyTest"
run L7 "NfyTemplateTest,NfyOpsHealthTest,NfySchemaMigrationTest"
run L8 "NfyStaticPageTest,NfySmokeTest"
echo "ALL_DONE $(date +%H:%M:%S)" >> "$SUM"
