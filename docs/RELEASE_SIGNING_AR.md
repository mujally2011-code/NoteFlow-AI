# تسليم وتوقيع إصدار NoteFlow AI 1.2.0

- يستخدم GitHub Actions الأمر `assembleRelease` ثم `zipalign` و`apksigner` لتوليد APK بنمط **release**؛ وليس إصدار Debug.
- إذا لم توجد أسرار المستودع `NF_RELEASE_KEYSTORE_B64` و`NF_RELEASE_KEYSTORE_PASSWORD`، يتم إنشاء مفتاح إصدار جديد داخل بيئة CI مع حفظ **نسخة مشفرة فقط** من ملف keystore وكلمة مروره في ملف `NoteFlow-AI-RELEASE-KEY-ENCRYPTED.p7m`.
- **ضروري**: المفتاح الخاص لفك تشفير ملف الاسترداد ليس ضمن GitHub ولا ZIP المصدر، ويجب حفظه بعيداً عن متناول الآخرين. لا تنشره. بعد استخراج `noteflow.jks` ضع ملفه وقيمة كلمة السر في أسرار مستودع GitHub للنسخ المستقبلية حتى تتمكن من تحديث التطبيق لدى المستخدمين دون إزالة النسخة السابقة.
- إن فقدت المفتاح أو ملف الاسترداد، فلن يمكن توقيع تحديثات بمفتاح الإصدار نفسه. إذا نجحت المحاولة مرتين قبل اعتماد keystore ثابت، فقد تستخدم المحاولة الثانية مفتاحاً جديداً.
- يثبت `apksigner verify` صحة توقيع APK، لكنه لا يثبت أن التطبيق يعمل على كل الأجهزة أو أن تكاملات Teams/Zoom تعمل؛ يجب إجراء اختبار جهاز حقيقي وخدمات OAuth قبل الإنتاج.
- تتطلب وظائف السحابة/المؤسسات إعداد خادم HTTPS وأسرار المزود وموافقات Teams وZoom، ولا تُضمَّن مفاتيح خاصة في APK.
- النسخة المصدرية تحتوي على شفرة الـcommit نفسه والـGradle Wrapper القياسي الذي ينتجه Gradle خلال سير العمل، وليس أية تسجيلات خاصة.

### فك تشفير مفتاح الإصدار بعد تحميل الملفات

```bash
openssl cms -decrypt -binary -inform DER \
 -in NoteFlow-AI-RELEASE-KEY-ENCRYPTED.p7m \
 -inkey NoteFlow-Release-Recovery-private.pem \
 -out signing-backup.tar.gz
# يُطلب منك إدخال كلمة مرور المفتاح الخاص؛ احفظ الملفين بأمان.
tar -xzf signing-backup.tar.gz
base64 -w 0 noteflow.jks     # ألصق الناتج في NF_RELEASE_KEYSTORE_B64
# ضع محتوى password.txt في NF_RELEASE_KEYSTORE_PASSWORD
```

لا تضع كلمات المرور أو ملفات keystore داخل GitHub Commit أو تذكرة عامة.
