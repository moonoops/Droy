package io.droyagent.androiduse;
import android.os.ParcelFileDescriptor;

interface IShizukuOperations {
    ParcelFileDescriptor execute(String action, in int[] values) = 0;
    void cancel() = 1;
    int uid() = 2;
    void destroy() = 16777114;
}
