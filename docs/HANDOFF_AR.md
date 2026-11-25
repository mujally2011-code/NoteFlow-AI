# استلام المصدر وإكمال البناء على GitHub

1. أنشئ مستودع GitHub فارغاً وامنح GitHub Plugin صلاحية الوصول إليه؛ الحساب المتصل حالياً لا يعرض أي مستودع.
2. فك الضغط عن حزمة المصدر وافتح المجلد في Android Studio، أو استنسخ `NoteFlow-AI-Phase2-v1.1.0-git.bundle` باستخدام `git clone FILE.bundle NoteFlow-AI`.
3. أضف عنوان مستودعك الحقيقي: `git remote add origin <YOUR_GITHUB_REPOSITORY_URL>` ثم `git push -u origin main`.
4. افتح تبويب Actions ونفّذ Workflow باسم `Android build, tests and reproducible debug release`. إذا نجح، يُنتج APK موقّع توقيع Debug وليس توقيع نشر؛ مع مصدر ZIP من commit نفسه وملف SHA256.
5. للتشغيل السحابي، شغّل FastAPI من مجلد `backend/` واضبط `OPENAI_API_KEY`, `NOTEFLOW_PAIRING_SECRET`, `NOTEFLOW_SIGNING_KEY` في البيئة الخاصة بالخادم. شغّل HTTPS أمام الخادم ولا ترسل الأسرار مع التطبيق.
6. لتشغيل تمييز المتحدثين، ضع في متغير `NOTEFLOW_DIARIZATION_MODEL` نموذجاً متوافقاً مع `diarized_json`؛ لا يوجد مزود مفعّل افتراضياً. النموذج التجريبي من OpenAI `gpt-4o-transcribe-diarize` قديم ومقرر إيقافه 2027-02-26.
7. أنشئ تجربة محاكاة أندرويد للتحقق من التطبيقات المحفوظة واستيراد الملفات، وراجع الإخراج العربي/الإنجليزي في PDF صادر من الهاتف فعلياً.

**نتائج التنفيذ المحلي الموجودة في المصدر:** `bash scripts/run_offline_checks.sh` يختبر الخادم والمنطق المحلي ويعطي نتائج حقيقية. اختبار Gradle الفعلي في بيئة إنشاء هذه النسخة أخفق قبل البدء بسبب تعذر الوصول إلى `services.gradle.org`؛ لا يوجد APK محلي. لم يتم إنشاء commit على GitHub نفسه.
