package com.dawn.exception;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.tencent.bugly.crashreport.CrashReport;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃捕获工具类
 * <p>
 * 捕获未处理的异常，自动写入本地文件，支持回调上传服务器。
 * 可选集成 Bugly 上报。日志文件按天生成，自动清理超过 7 天的文件。
 * </p>
 *
 * <pre>
 * 初始化（Application.onCreate）：
 *   LCrashHandlerUtil.getInstance()
 *       .init(this)
 *       .setListener((context, crashReport) -> {
 *           // 上传 crashReport 到你的服务器
 *       });
 *
 * 可选 Bugly：
 *   LCrashHandlerUtil.getInstance().initBugly(this, "your_app_id", "your_channel", BuildConfig.DEBUG);
 * </pre>
 */
@SuppressWarnings("unused")
public class LCrashHandlerUtil implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "LCrashHandlerUtil";

    private static final String CRASH_FILE_PREFIX = "crash_";
    private static final String CRASH_FILE_SUFFIX = ".trace";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final long MAX_FOLDER_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int RETENTION_DAYS = 7;

    private static final LCrashHandlerUtil sInstance = new LCrashHandlerUtil();

    private Thread.UncaughtExceptionHandler mDefaultHandler;
    private Context mContext;
    private String mCrashPath;
    private OnCrashListener mListener;

    private LCrashHandlerUtil() { }

    public static LCrashHandlerUtil getInstance() {
        return sInstance;
    }

    // ==================== 初始化 ====================

    /**
     * 初始化崩溃捕获
     *
     * @param context 上下文
     * @return 当前实例（链式调用）
     */
    public LCrashHandlerUtil init(Context context) {
        return init(context, null);
    }

    /**
     * 初始化崩溃捕获
     *
     * @param context       上下文
     * @param customCrashPath 自定义崩溃日志路径，为空则使用默认路径
     * @return 当前实例（链式调用）
     */
    public LCrashHandlerUtil init(Context context, String customCrashPath) {
        mContext = context.getApplicationContext();
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);

        if (TextUtils.isEmpty(customCrashPath)) {
            mCrashPath = getDefaultCrashPath(mContext);
        } else {
            mCrashPath = customCrashPath;
            if (!mCrashPath.endsWith("/")) {
                mCrashPath += "/";
            }
        }

        File dir = new File(mCrashPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 清理过期日志
        cleanExpiredCrashLogs();

        return this;
    }

    /**
     * 初始化 Bugly 崩溃上报
     *
     * @param context 上下文
     * @param appId   Bugly 应用 ID
     * @param isDebug 是否开启 Debug 模式
     */
    public void initBugly(Context context, String appId, boolean isDebug) {
        CrashReport.initCrashReport(context.getApplicationContext(), appId, isDebug);
    }

    /**
     * 初始化 Bugly 崩溃上报（带渠道）
     *
     * @param context 上下文
     * @param appId   Bugly 应用 ID
     * @param channel 渠道标识
     * @param isDebug 是否开启 Debug 模式
     */
    public void initBugly(Context context, String appId, String channel, boolean isDebug) {
        CrashReport.UserStrategy strategy = new CrashReport.UserStrategy(context.getApplicationContext());
        strategy.setAppChannel(channel);
        CrashReport.initCrashReport(context.getApplicationContext(), appId, isDebug, strategy);
    }

    // ==================== 回调设置 ====================

    /**
     * 设置崩溃回调监听器
     *
     * @param listener 崩溃回调
     * @return 当前实例（链式调用）
     */
    public LCrashHandlerUtil setListener(OnCrashListener listener) {
        mListener = listener;
        return this;
    }

    /**
     * 崩溃回调接口
     */
    public interface OnCrashListener {
        /**
         * 崩溃发生时回调
         *
         * @param context     上下文
         * @param crashReport 崩溃报告完整内容（包含设备信息和堆栈）
         */
        void onCrash(Context context, String crashReport);
    }

    // ==================== 核心处理 ====================

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        // 1. 构建崩溃报告
        String crashReport = buildCrashReport(thread, ex);

        // 2. 写入本地文件
        saveCrashToFile(crashReport);

        // 3. 回调通知使用方
        if (mListener != null) {
            try {
                mListener.onCrash(mContext, crashReport);
            } catch (Exception e) {
                Log.e(TAG, "Crash listener error", e);
            }
        }

        // 4. 交给默认处理器
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(thread, ex);
        } else {
            Process.killProcess(Process.myPid());
        }
    }

    // ==================== 文件管理接口 ====================

    /**
     * 获取崩溃日志存储目录
     */
    public String getCrashPath() {
        return mCrashPath;
    }

    /**
     * 获取所有崩溃日志文件（按时间从旧到新排序）
     *
     * @return 崩溃日志文件列表
     */
    public List<File> getCrashFiles() {
        List<File> result = new ArrayList<>();
        if (mCrashPath == null) return result;

        File dir = new File(mCrashPath);
        if (!dir.exists() || !dir.isDirectory()) return result;

        File[] files = dir.listFiles(file ->
                file.isFile()
                        && file.getName().startsWith(CRASH_FILE_PREFIX)
                        && file.getName().endsWith(CRASH_FILE_SUFFIX));

        if (files == null || files.length == 0) return result;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        result.addAll(Arrays.asList(files));
        return result;
    }

    /**
     * 清理超过保留天数的崩溃日志
     */
    public void cleanExpiredCrashLogs() {
        if (mCrashPath == null) return;

        File dir = new File(mCrashPath);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        long expireTime = System.currentTimeMillis() - (long) RETENTION_DAYS * 24 * 60 * 60 * 1000;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < expireTime) {
                file.delete();
            }
        }

        // 如果文件夹总大小超限，全部清理
        if (getFolderSize(dir) > MAX_FOLDER_SIZE) {
            clearAllCrashLogs();
        }
    }

    /**
     * 清除所有崩溃日志
     */
    public void clearAllCrashLogs() {
        if (mCrashPath == null) return;

        File dir = new File(mCrashPath);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    // ==================== 报告构建 ====================

    private String buildCrashReport(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder();

        // 时间
        sb.append("Time: ").append(formatDateTime(new Date())).append("\n");
        // 线程
        sb.append("Thread: ").append(thread.getName()).append("\n");

        // 设备信息
        try {
            PackageManager pm = mContext.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(mContext.getPackageName(), PackageManager.GET_ACTIVITIES);
            sb.append("App Version: ").append(pi.versionName).append("_").append(pi.versionCode).append("\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("App Version: unknown\n");
        }
        sb.append("OS Version: ").append(Build.VERSION.RELEASE).append("_").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Vendor: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("CPU ABI: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");

        sb.append("\n");

        // 完整堆栈
        extractFullStackTrace(ex, sb);

        return sb.toString();
    }

    private void extractFullStackTrace(Throwable throwable, StringBuilder output) {
        if (throwable == null) return;

        output.append("Exception: ").append(throwable.getClass().getName()).append("\n");
        output.append("Message: ").append(throwable.getMessage()).append("\n");

        for (StackTraceElement element : throwable.getStackTrace()) {
            output.append("\tat ").append(element.toString()).append("\n");
        }

        if (throwable.getCause() != null) {
            output.append("\nCaused by: ");
            extractFullStackTrace(throwable.getCause(), output);
        }

        for (Throwable suppressed : throwable.getSuppressed()) {
            output.append("\nSuppressed: ");
            extractFullStackTrace(suppressed, output);
        }
    }

    // ==================== 文件写入 ====================

    private void saveCrashToFile(String content) {
        if (mCrashPath == null) return;

        File dir = new File(mCrashPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String fileName = mCrashPath + CRASH_FILE_PREFIX + formatDate(new Date()) + CRASH_FILE_SUFFIX;
        File file = new File(fileName);

        BufferedWriter writer = null;
        try {
            // 追加模式，同一天多次崩溃写入同一文件
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), "UTF-8"));
            writer.write("═══════════════════════════════════════════════════\n");
            writer.write(content);
            writer.write("\n\n");
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save crash log", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    private String getDefaultCrashPath(Context context) {
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir != null && externalDir.exists()) {
            return externalDir.getAbsolutePath() + "/Crash/";
        }
        return context.getFilesDir().getAbsolutePath() + "/Crash/";
    }

    private long getFolderSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                size += getFolderSize(file);
            } else {
                size += file.length();
            }
        }
        return size;
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(date);
    }

    private String formatDateTime(Date date) {
        return new SimpleDateFormat(DATETIME_PATTERN, Locale.getDefault()).format(date);
    }
}
