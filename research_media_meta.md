# متطلبات Meta للنشر متعدد الوسائط

مصادر Meta الرسمية التي تمت قراءتها في 17 أغسطس 2026:

- https://developers.facebook.com/documentation/pages-api/posts — دليل Posts، محدث 17 أبريل 2026.
- https://developers.facebook.com/docs/graph-api/reference/page/photos/ — مرجع Page Photos.
- https://developers.facebook.com/docs/graph-api/reference/page/videos/ — مرجع Page Videos.
- https://developers.facebook.com/documentation/video-api/guides/publishing — دليل Video API Publishing.

الخلاصة التنفيذية:

1. المنشور النصي يستخدم `POST /v26.0/{page-id}/feed` مع `message` و`published=true`، وتعيد Meta `id` للمنشور.
2. الصورة المفردة يمكن نشرها عبر `POST /v26.0/{page-id}/photos` باستخدام `source` كـ multipart/form-data أو `url` لصورة مستضافة. النشر الناجح يعيد `id` للصورة و`post_id` للمنشور.
3. المنشور متعدد الصور يحتاج رفع كل صورة أولاً مع `published=false`، ثم نشر `/{page-id}/feed` مع `attached_media[n]` بقيم `{"media_fbid":"photo_id"}`.
4. الفيديو يحتاج Page access token مع صلاحية `pages_manage_posts`، والشخص طالب الرمز يجب أن يملك مهمة `CREATE_CONTENT`. دليل Video API يوصي برفع الفيديو عبر Resumable Upload API إلى `graph.facebook.com/{APP_ID}/uploads` باستخدام User access token، ثم نشره عبر `https://graph-video.facebook.com/v26.0/{PAGE_ID}/videos` باستخدام `fbuploader_video_file_chunk`.
5. دليل Meta يذكر صلاحيات `pages_manage_engagement`, `pages_manage_posts`, `pages_read_engagement`, `pages_read_user_engagement`، و`publish_video` عند نشر الفيديو.
6. لا يمكن جمع صورة وفيديو في طلب منشور واحد بالطريقة نفسها؛ يلزم فصل المسارات، ولذلك سيحدد التطبيق نوع الوسائط ويمنع الخلط غير المدعوم بدلاً من إعلان نجاح غير مؤكد.
