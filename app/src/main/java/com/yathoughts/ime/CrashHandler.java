package com.yathoughts.ime;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃捕获：把未捕获异常写入应用私有目录 crash.txt，
 * 下次打开设置页时展示给用户（也可复制给开发者）。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String FILE = "crash.txt";
    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context ctx) {
        appContext = ctx.getApplicationContext();
        previous = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            FileWriter fw = new FileWriter(new File(appContext.getFilesDir(), FILE), true);
            fw.write("==== crash @ " + time + " (thread " + t.getName() + ") ====\n");
            fw.write(sw.toString());
            fw.write("\n");
            fw.close();
        } catch (Throwable ignored) {
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        }
    }

    /** 读取最近崩溃日志（无则返回 null） */
    public static String readLast(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            byte[] all = readAll(f);
            String s = new String(all, java.nio.charset.StandardCharsets.UTF_8);
            // 只返回最后一段
            int idx = s.lastIndexOf("==== crash @ ");
            return idx >= 0 ? s.substring(idx) : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 清除日志 */
    public static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        new File(context.getFilesDir(), FILE).delete();
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        } finally {
            in.close();
        }
    }
}
