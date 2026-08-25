@echo off
set JAVA_HOME=X:\115\OpenJDK17U-jdk_x64_windows_hotspot_17.0.19_10\jdk-17.0.19+10
set ANDROID_HOME=C:\Android\Sdk
X:\115\gradle-9.5.1-all\gradle-9.5.1\bin\gradle.bat -p C:\Android\MangaReader assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m" 2>&1
