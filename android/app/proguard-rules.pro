# Keep the QR encoder and crypto helpers intact; they are called reflectively by nothing,
# but R8 is off by default in this project.
-keep class cn.edu.ahu.cardqr.** { *; }
