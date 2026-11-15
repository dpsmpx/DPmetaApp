# R8 для release DPmetaApp (D-156): сжатие и оптимизация без обфускации.
# Имена классов остаются: сообщения об ошибках показывают e.javaClass.simpleName, а
# экраны и форматы местами опираются на имя класса — обфускация их бы сломала.
-dontobfuscate
# Стек-трейсы журнала отладки (D-39) — с номерами строк.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Библиотеки с отражением — целиком, пока приёмка на телефоне не покажет, что можно уже.
# JSch (SSH к target): шифры, обмен ключами и подписи — Class.forName по имени из конфигурации.
-keep class com.jcraft.jsch.** { *; }
# JGit (клоны, D-65): тексты NLS заполняются отражением, транспорт — ServiceLoader.
-keep class org.eclipse.jgit.** { *; }
# Редактор (Sora) и TextMate: грамматики и темы читаются Gson в классы модели.
-keep class io.github.rosemoe.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.snakeyaml.engine.** { *; }
# Регулярные выражения TextMate: кодировки jcodings создаются по имени класса.
-keep class org.jcodings.** { *; }
-keep class org.joni.** { *; }
# Подпись APK (apksig, D-127): алгоритмы — через JCA по имени.
-keep class com.android.apksig.** { *; }

# Аннотации и необязательные зависимости библиотек, которых нет на Android.
-dontwarn org.slf4j.**
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.errorprone.annotations.**
# JSch: необязательные провайдеры — BouncyCastle (свои шифры ChaCha20/Argon2/ML-KEM), JNA и
# сокеты Unix (агент ssh-agent на ПК), GSS-API (Kerberos), log4j; на Android их нет, и JSch
# выбирает их, только если класс найден.
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.logging.log4j.**
# JGit: сведения JMX о процессе и ProcessHandle (Java 9+) — только для отладки на JVM.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn com.sun.management.**
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.ProcessHandle$Info
-dontwarn kotlin.Cloneable$DefaultImpls
