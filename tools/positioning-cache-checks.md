# Positioning lookup cache checks

Run against a connected Android development device with framework internals
matching this platform checkout:

```sh
ADB=/path/to/adb tools/test-positioning-cache.sh SERIAL
```

Optional second and third arguments select a replacement source directory
(`src/app/grapheneos/networklocation`) and output directory. This permits the
same checks to run against a frozen source snapshot. The harness compiles the
actual Wi-Fi/cell cache, positioning-data and TimedLruCache classes. It uses
Android's real LruCache, clock and background handler in `app_process`.

The services return deterministic local records. No radio scan, network request,
provider-setting change or application installation occurs. Temporary device
files are removed after success or failure. Build outputs stay in the selected
local output directory.

The 32 checks cover both cache implementations: immediate positive/negative
record replacement, explicit invalidation versus response omission, observed
identity filtering, duplicate identities and request thresholds, stable result
ordering for unchanged records, cached-only operation and failed-lookup
fallback. Full PositioningData objects include altitude and raw metadata, so
refreshing a record cannot silently retain the older vertical fields.

These checks establish lookup consistency and availability. They do not measure
absolute position accuracy, calibrate database radii or assign a confidence
definition to an upstream field. They do not impose a new cache-expiry policy.
