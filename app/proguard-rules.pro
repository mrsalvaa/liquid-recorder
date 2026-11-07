# SPDX-License-Identifier: GPL-3.0-or-later
# Modelos guardados con kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class dev.liquidrecorder.**$$serializer { *; }
-keepclassmembers class dev.liquidrecorder.** { *** Companion; }
