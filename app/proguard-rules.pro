# Default Android rules, plus:
# ONNX Runtime calls into these classes from native code.
-keep class ai.onnxruntime.** { *; }
