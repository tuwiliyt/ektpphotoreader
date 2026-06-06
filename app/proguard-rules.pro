# ProGuard rules for e-KTP Reader

# Keep NFC-related classes
-keep class android.nfc.** { *; }

# Keep the app's model classes
-keep class com.ektp.reader.nfc.** { *; }

# Keep ViewBinding classes
-keep class com.ektp.reader.databinding.** { *; }
