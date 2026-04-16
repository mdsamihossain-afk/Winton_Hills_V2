-keep class com.winton.validationshell.engine.Engine {
    public *;
}
-keep class com.winton.validationshell.engine.hardware.HardwareProfile { *; }
-keep class com.winton.validationshell.engine.hardware.HardwareProfile$* { *; }
-keep class com.winton.validationshell.engine.policy.PolicyConfig { *; }
-keep class com.winton.validationshell.engine.policy.PolicyConfig$* { *; }
-keep class com.winton.validationshell.engine.policy.BiquadBandConfig { *; }
-keep class com.winton.validationshell.engine.latency.LatencyTracker { public *; }
