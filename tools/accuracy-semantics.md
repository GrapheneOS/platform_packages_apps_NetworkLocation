# Accuracy semantics

Apple's raw positioning-database accuracy is a scale in meters whose
confidence percentile has not been established. Preserve optional confidence
as uninterpreted metadata, distinguishing absence from an explicit zero.

The generic solver uses squared uncertainty scales for residual tests,
weighting, and finite uncertainty probes. Wi-Fi intentionally uses six-sigma
output scaling: its reporting adapter divides the square root by six.
The cellular adapter reports the square root directly. These are distinct
output conventions despite the shared field name.

The conventions specify how values are represented and reported. Statistical
coverage is a separate question: Android consumers expect a 68% horizontal
radius, and broad field calibration of that coverage remains unverified.

Wi-Fi database altitude is labeled as mean sea level based on the documented
working interpretation. Missing height or vertical accuracy stays absent.
The decoder's label alone does not change the reporting altitude reference.
