package io.droyagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fixed fictional cases only; the receiver runs under the separate test-APK UID. */
public final class PhoneNotificationFixtureReceiver extends BroadcastReceiver {
    private static final String TAG = "droy-phone-retention-fixture";
    @Override public void onReceive(Context context, Intent intent) {
        String suffix = intent.getStringExtra("suffix");
        if (suffix == null || !suffix.matches("[a-f0-9]{12}")) return;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (intent.hasExtra("cancelId")) { notifications.cancel(TAG, intent.getIntExtra("cancelId", 0)); return; }
        if (intent.getBooleanExtra("clear", false)) {
            for (int id : new int[]{731, 732, 733, 734}) notifications.cancel(TAG, id);
            for (String kind : new String[]{"ad", "ordinary", "important", "locked"}) notifications.deleteNotificationChannel("droy_phone_" + suffix + "_" + kind);
            return;
        }
        String kind = intent.getStringExtra("kind"), title, text;
        if ("ad".equals(kind)) {
            title = "虚构商城 · 限时促销";
            text = "【虚构广告测试】全场大促，领取50元购物优惠券，热门商品低至一折！点击立即抢购，订阅促销活动。";
        } else if ("ordinary".equals(kind)) {
            title = "虚构阅读器 · 离线文章已保存";
            text = "【虚构通知测试】你刚才选择的文章已保存到离线阅读列表，离线时也能打开，有空时可以继续阅读。";
        } else if ("important".equals(kind) || "locked".equals(kind)) {
            title = "虚构设备监控 · 机房温度严重异常";
            text = "【虚构通知测试】机房温度已达85°C，超过安全阈值，设备存在过热停机风险，请立即查看并处理温度异常。";
        } else return;
        String channel = "droy_phone_" + suffix + "_" + kind;
        NotificationChannel fixture = new NotificationChannel(channel, "Fictional Droy phone evaluation", NotificationManager.IMPORTANCE_LOW);
        fixture.setSound(null, null); fixture.enableVibration(false); notifications.createNotificationChannel(fixture);
        notifications.notify(TAG, intent.getIntExtra("id", 0), new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).build());
        setResultData("PHONE_FIXTURE_POSTED " + context.getPackageName() + " uid=" + android.os.Process.myUid()
                + " enabled=" + notifications.areNotificationsEnabled());
    }
}
