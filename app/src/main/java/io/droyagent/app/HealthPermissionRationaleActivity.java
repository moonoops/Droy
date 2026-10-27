package io.droyagent.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Android 16 links here from the heart-rate permission screen. */
public final class HealthPermissionRationaleActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(NativeLocale.text("身体传感器数据用途", "Body sensor data use"));
        LinearLayout content = NfcUi.body(this);
        TextView title = NfcUi.text(this, NativeLocale.text("身体传感器数据用途", "Body sensor data use"), 24);
        content.addView(title);
        TextView explanation = NfcUi.text(this, NativeLocale.text(
            "Droy 在你开启身体传感器能力并发起任务后，读取本机可用的心率或心跳传感器数据。授予系统权限不会自动开启采样或持续记录。\n\n任务读取的数据可发送给你配置的当前模型进行分析；模型服务的数据处理遵循该服务的政策和你在 Droy 中的处理授权。保存的数据位于应用私有存储，导出由你选择位置。\n\n你可以在 Droy 的权限设置关闭身体传感器能力或暂停手机操作，也可以在系统设置撤销权限。停止采样会释放传感器监听；已保存的结果需另行删除。此能力不读取 Health Connect 的历史健康记录。",
            "After you enable body sensor access and start a task, Droy reads heart rate or heartbeat data from available sensors on this phone. Granting system permission does not start sampling or continuous recording.\n\nData read for the task may be sent to your configured current model for analysis, subject to that service’s data policy and your processing authorization in Droy. Saved data stays in app-private storage; you choose where to export it.\n\nYou can disable body sensor access or pause phone operations in Droy, or revoke system permission. Stopping sampling releases sensor listeners; saved results must be deleted separately. This capability does not read historical health records from Health Connect."), 16);
        content.addView(explanation);
        Button close = new Button(this);
        close.setText(NativeLocale.text("返回", "Back"));
        close.setOnClickListener(view -> finish());
        content.addView(close);
        NfcUi.show(this, content);
    }
}
