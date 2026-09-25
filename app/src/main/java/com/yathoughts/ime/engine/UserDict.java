package com.yathoughts.ime.engine;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;

/**
 * 用户词自学习：word → (pinyinKey, score)，SharedPreferences + JSON 持久化。
 * 上限 5000 条，超出时丢弃分值最低的一半。
 */
public final class UserDict {

    private static final String PREFS = "ime_user_dict";
    private static final String KEY_DATA = "data";
    private static final int MAX_ENTRIES = 5000;

    private final HashMap<String, Entry> map = new HashMap<>();
    private boolean dirty = false;

    public static final class Entry {
        public final String word;
        public String pinyinKey;
        public long score;

        Entry(String word, String pinyinKey, long score) {
            this.word = word;
            this.pinyinKey = pinyinKey;
            this.score = score;
        }
    }

    public synchronized void load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_DATA, null);
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String word = it.next();
                JSONObject o = obj.optJSONObject(word);
                if (o == null) continue;
                map.put(word, new Entry(word, o.optString("k", ""), o.optLong("s", 1)));
            }
        } catch (JSONException ignored) {
        }
    }

    public synchronized Entry get(String pinyinKey) {
        return map.get(pinyinKey);   // map 以 pinyinKey 为键
    }

    public synchronized void bump(String word, String pinyinKey) {
        Entry e = map.get(pinyinKey);
        if (e != null && e.word.equals(word)) {
            e.score = Math.min(e.score + 1, 500_000);
        } else {
            e = new Entry(word, pinyinKey, 10);
            map.put(pinyinKey, e);
        }
        dirty = true;
        if (map.size() > MAX_ENTRIES) evictHalf();
    }

    public synchronized void clear(Context context) {
        map.clear();
        dirty = true;
        persist(context);
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void persistIfDirty(Context context) {
        if (dirty) persist(context);
    }

    private void evictHalf() {
        // 分值最低的一半淘汰
        java.util.List<Entry> list = new java.util.ArrayList<>(map.values());
        list.sort((a, b) -> Long.compare(a.score, b.score));
        int drop = list.size() / 2;
        for (int i = 0; i < drop; i++) {
            map.remove(list.get(i).pinyinKey);
        }
    }

    private void persist(Context context) {
        try {
            JSONObject obj = new JSONObject();
            for (Entry e : map.values()) {
                JSONObject o = new JSONObject();
                o.put("k", e.pinyinKey);
                o.put("s", e.score);
                obj.put(e.word, o);
            }
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_DATA, obj.toString()).apply();
            dirty = false;
        } catch (JSONException ignored) {
        }
    }
}
