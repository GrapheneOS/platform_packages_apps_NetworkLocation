# Cell observation checks

From the Android source root, after building `NetworkLocation`:

```sh
ADB=/path/to/adb bash packages/apps/NetworkLocation/tools/test-cell-observations.sh SERIAL
```

The runner compiles the actual observation selector against the built Android
framework and executes it with framework `CellInfo` objects on the development
device. It requires the matching platform build and Kotlin/R8 tools in this
checkout. It does not install an app or change radio settings; a temporary
directory under `/data/local/tmp` is removed when the runner exits.

The 14 checks cover unavailable, negative and future timestamps, both valid
timestamp boundaries, genuine older observations, duplicate order, complete
LTE/NR/GSM/WCDMA identities and unsupported or unavailable identities. NR
identities retain all 36 bits. Default Android timestamps use a nanosecond
sentinel; `timestampMillis` returns that sentinel divided by one million.

Optional second and third arguments select another selector source file and
output directory. An alternate source must define the same package and
`selectCellObservations` entry point. This supports reproducing the former
behavior without editing the production file.

These are controlled data-contract checks. They do not measure how often a
modem supplies invalid observations, field position error or radius coverage.
