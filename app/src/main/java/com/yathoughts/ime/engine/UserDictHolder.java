package com.yathoughts.ime.engine;

import android.content.Context;

/**
 * 引擎单例桥：设置页与 Service 共享同一引擎实例（同进程）。
 */
public final class UserDictHolder {

    private static volatile PinyinEngine instance;

    public static PinyinEngine get() {
        if (instance == null) {
            synchronized (UserDictHolder.class) {
                if (instance == null) instance = new PinyinEngine();
            }
        }
        return instance;
    }

    public static void persist(Context appContext) {
        PinyinEngine e = instance;
        if (e != null) e.persistUserDict(appContext);
    }
}
