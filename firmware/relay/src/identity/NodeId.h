#pragma once

#include <Arduino.h>

// Stable Neyra-style node ID for this bridge, derived from the Bluetooth MAC:
//   HMR-4e455952-4c4f-8000-8000-<12 hex digits of the BT MAC>
// "4e455952-4c4f" is ASCII "NEYRLO"; the 8s mark an RFC 9562 version-8
// (custom) UUID with the standard variant, so Android's UUID.fromString accepts it.
// Not advertised yet; used for logging and the future RELAY discovery payload.
String bridgeNodeId();
