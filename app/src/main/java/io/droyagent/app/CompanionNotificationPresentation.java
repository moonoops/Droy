package io.droyagent.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class CompanionNotificationPresentation {
    private static final String PRESENTATION_KEY = "droyCompanionPresentation";
    private CompanionNotificationPresentation() { }

    static Context localized(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(NativeLocale.active()));
        return context.createConfigurationContext(config);
    }

    static String text(Context context, int id) { return localized(context).getString(id); }

    static boolean matches(Notification notification, String companion, List<JSONObject> messages,
                           int unread, String channel) throws Exception {
        return presentationKey(NativeLocale.active(), companion, messages, unread, channel)
            .equals(notification.extras.getString(PRESENTATION_KEY));
    }

    private static String presentationKey(String language, String companion, List<JSONObject> messages,
                                          int unread, String channel) throws Exception {
        JSONArray rows = new JSONArray();
        for (JSONObject message : messages) rows.put(new JSONArray().put(message.getString("messageId"))
            .put(message.getString("name")).put(message.getString("text")).put(message.getLong("sentAt"))
            .put(message.optString("avatar")));
        String value = new JSONArray().put(language).put(companion).put(channel).put(unread).put(rows).toString();
        return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
    }

    static Notification build(Context context, String companion, List<JSONObject> messages, int unread,
                              String channel, boolean onlyAlertOnce) throws Exception {
        Context locale = localized(context);
        JSONObject latest = messages.get(messages.size() - 1);
        String name = latest.getString("name");
        String shortcutId = "companion:" + companion;
        Intent target = new Intent(context, NativeAppActivity.class).setAction(Intent.ACTION_VIEW)
            .putExtra("companionId", companion)
            .setData(new Uri.Builder().scheme("droy").authority("companion").appendPath(companion).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, target,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Icon avatar = avatar(context, name, latest.optString("avatar"));
        Person sender = new Person.Builder().setName(name).setKey(companion).setBot(true).setIcon(avatar).build();
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId).setShortLabel(name).setLongLived(true)
            .setIntent(new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).setIcon(avatar)
            .setPersons(new Person[]{sender}).setLocusId(new LocusId(shortcutId)).build();
        context.getSystemService(ShortcutManager.class).pushDynamicShortcut(shortcut);
        Notification.MessagingStyle style = new Notification.MessagingStyle(
            new Person.Builder().setName(locale.getString(R.string.cnotice_you)).build()).setGroupConversation(false);
        for (JSONObject message : messages)
            style.addMessage(new Notification.MessagingStyle.Message(message.getString("text"), message.getLong("sentAt"), sender));
        Notification publicVersion = new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_droy)
            .setContentTitle(locale.getString(R.string.cnotice_private)).setCategory(Notification.CATEGORY_MESSAGE).build();
        Notification.Builder notification = new Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_droy).setLargeIcon(avatar).setStyle(style)
            .setGroup("droy-companion-messages:" + companion)
            .setGroupAlertBehavior(onlyAlertOnce ? Notification.GROUP_ALERT_SUMMARY : Notification.GROUP_ALERT_ALL)
            .setCategory(Notification.CATEGORY_MESSAGE).setContentIntent(open).setAutoCancel(false)
            .setShortcutId(shortcutId).setLocusId(new LocusId(shortcutId)).addPerson(sender)
            .setWhen(latest.getLong("sentAt")).setShowWhen(true).setOnlyAlertOnce(onlyAlertOnce)
            .setNumber(unread).setSubText(locale.getResources().getQuantityString(R.plurals.cnotice_unread, unread, unread))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setDeleteIntent(action(context, companion, latest.getString("messageId"), CompanionNotificationReceiver.DISMISS));
        notification.addAction(new Notification.Action.Builder(null, locale.getString(R.string.cnotice_open), open).build());
        notification.addAction(new Notification.Action.Builder(null, locale.getString(R.string.cnotice_mark_read),
            action(context, companion, latest.getString("messageId"), CompanionNotificationReceiver.READ))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build());
        Notification result = notification.build();
        result.extras.putString(PRESENTATION_KEY, presentationKey(locale.getResources().getConfiguration().getLocales().get(0).getLanguage(),
            companion, messages, unread, channel));
        return result;
    }

    private static PendingIntent action(Context context, String companion, String message, String action) {
        Intent intent = new Intent(context, CompanionNotificationReceiver.class).setAction(action)
            .setData(new Uri.Builder().scheme("droy").authority("companion-notification").appendPath(companion).appendPath(message).appendPath(action).build())
            .putExtra("companionId", companion).putExtra("messageId", message);
        // Message identity is part of the PendingIntent identity: a late action cannot consume a newer message.
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String assetName(String value) {
        if (value == null || !value.matches("/droy/companions/assets/[A-Za-z0-9_-]+\\.png(?:\\?v=[A-Za-z0-9_.-]+)?")) return null;
        String path = value.split("\\?", 2)[0];
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Icon avatar(Context context, String name, String url) {
        String asset = assetName(url);
        if (asset != null) {
            try {
                File directory = new File(context.getFilesDir(), "runtime/mobile/companions/assets").getCanonicalFile();
                File file = new File(directory, asset).getCanonicalFile();
                if (file.getParentFile().equals(directory) && file.isFile() && file.length() <= 10 * 1024 * 1024) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getPath(), options);
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        int sample = 1;
                        while (options.outWidth / sample > 256 || options.outHeight / sample > 256) sample *= 2;
                        options.inJustDecodeBounds = false; options.inSampleSize = sample;
                        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                        if (bitmap != null) return Icon.createWithBitmap(bitmap);
                    }
                }
            } catch (IOException unavailable) {
                android.util.Log.w("DroyNotifications", "COMPANION_AVATAR_UNAVAILABLE");
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(225, 223, 249)); canvas.drawCircle(48, 48, 48, paint);
        paint.setColor(Color.rgb(65, 63, 117)); paint.setTextSize(44); paint.setTextAlign(Paint.Align.CENTER);
        String initial = name.substring(0, name.offsetByCodePoints(0, 1));
        canvas.drawText(initial, 48, 48 - (paint.ascent() + paint.descent()) / 2, paint);
        return Icon.createWithBitmap(bitmap);
    }
}
