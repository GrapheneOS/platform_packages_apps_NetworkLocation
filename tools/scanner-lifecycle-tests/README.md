# Scanner lifecycle checks

Run `bash packages/apps/NetworkLocation/tools/test-scanner-lifecycle.sh`
from the Android checkout. Optional arguments select a networklocation source
directory and an output directory, permitting before/after checks.

The harness compiles the actual cellular, Wi-Fi and RTT scanner sources with
the real Kotlin coroutine runtime. Small Android substitutes control callback
delivery, cancellation and executor ownership. Checks cover successful results,
ordinary errors, duplicate terminal callbacks, callbacks after cancellation,
prompt cancellation, filtering, repeated requests and unavailable hardware.

These are deterministic lifecycle tests, not radio measurements. The callback
substitutes deliberately exercise late and duplicate deliveries; they do not
establish how frequently those cases occur on a particular modem or Wi-Fi chip.
The RTT option remains experimental and is not enabled by these checks.
