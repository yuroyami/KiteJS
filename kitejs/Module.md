# Module kitejs

KiteJS with the Rhino engine underneath, under the coordinates KiteJS had before it could run more
than one engine. It brings in kitejs-api and kitejs-rhino and keeps the old `KiteJs { }`, which
opens Rhino, as a deprecated shortcut for `KiteJs(Rhino) { }`. New code depends on kitejs-api and
the engine it wants instead. This module goes away at 1.0.
