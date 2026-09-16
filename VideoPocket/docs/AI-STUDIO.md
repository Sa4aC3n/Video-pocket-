# فتح المشروع في Google AI Studio أو Android Studio

## ما داخل ZIP؟

المصدر الكامل **لنسخة اختبار المحرك الحالية**، وليس كل خصائص التطبيق النهائي. Kotlin + Compose، مشروع Gradle بوحدة app واحدة، يدعم ARM64 وx86_64. لا يحتاج Gemini API key أو Firebase أو سيرفر.

## Google AI Studio

وفق توثيق Google بتاريخ الاطلاع 15 سبتمبر 2026، وضع Build يدعم اختيار Android وتشغيل Kotlin/Compose داخل محاكي في المتصفح. التوثيق العام يذكر Import from GitHub عبر زر (+). وثيقة Android تذكر أيضًا قيودًا على NDK/C/C++؛ مشروعنا لا يبني C/C++ محليًا لكنه يضم مكتبات أصلية جاهزة من Maven، لذا توافق تشغيلها في AI Studio **غير مختبر وغير مضمون**.

1. فك ZIP مع الحفاظ على بنية المجلدات.
2. في AI Studio افتح Build واختر Android، وليس Web.
3. استخدم آلية استيراد المشروع التي تعرضها واجهتك. المسار الموثق عمومًا هو (+) → Import from GitHub؛ ارفع محتويات مجلد VideoPocket إلى مستودعك أولًا إن اخترت هذا المسار. لم يُرفع المشروع إلى أي حساب بالنيابة عنك.
4. لا تفترض أن إرفاق ZIP في دردشة عادية يستورد مشروع Gradle أو يشغله. إن لم يتوفر استيراد Android في حسابك، افتحه في Android Studio محليًا.
5. استخدم النص التالي مع مساعد AI Studio بعد استيراد الملفات:

```text
Open this existing single-module native Android project as Kotlin + Jetpack Compose, not as a web app. Read README.md and docs/DEVICE-TEST.md. This is VideoPocket's technical engine probe, not the completed downloader product. Preserve its real yt-dlp and FFmpeg integration and its GPL license. Do not replace downloads with mock results or a server service. Use JDK 17 and Android SDK 35, then run ./gradlew assembleDebug testDebugUnitTest lintDebug. Install the debug APK on the Android emulator and report its actual ABI and page size. Test app launch and engine initialization first. If this environment cannot run the packaged native libraries, report the exact limitation rather than claiming successful support. Keep the physical ARM64 acceptance gate pending.
```

## Android Studio — مسار تشغيل محلي بديل

1. فك ZIP ثم اختر Open وحدد مجلد VideoPocket الذي يحتوي settings.gradle.kts.
2. استخدم JDK 17 للـGradle، وثبّت Android SDK 35 وBuild Tools 35.0.0 من SDK Manager.
3. انتظر Gradle Sync.
4. من Device Manager أنشئ هاتفًا افتراضيًا Android 10+ بمعمارية x86_64 (أو ARM64 على جهاز مناسب).
5. اختر وحدة app واضغط Run.
6. نفّذ خطوات docs/DEVICE-TEST.md. وجود التطبيق في المحاكي لا يثبت نجاح تنزيلات المواقع؛ جرّب روابط عامة مسموحًا بتنزيلها فعليًا.

## لا ترفع هذه الملفات

الأرشيف يستبعد local.properties و.gradle وbuild وملفات التوقيع وذاكرة أدوات البناء المحلية. يحتوي gradle-wrapper.jar الضروري للبناء، بينما يحصل Gradle على تبعيات Maven عند البناء.

المراجع:
- https://ai.google.dev/gemini-api/docs/aistudio-android
- https://ai.google.dev/gemini-api/docs/aistudio-build-mode
