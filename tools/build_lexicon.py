#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清简输入法词库生成器

输入:
  /tmp/lexicon/pinyin.tsv      (pinyin-data 格式: U+XXXX: pinyin1,pinyin2  # 汉字)
  /tmp/lexicon/jieba_dict.txt  (jieba 格式: 词 词频 词性)

输出 (app/src/main/assets/lexicon/):
  lex.bin  单字 + 词组合一二进制库

二进制布局:
  int32 magic 'QJL2'   (QJL1 为旧版无简拼索引)
  int32 keyCount                     -- 拼音 key 数（key = 无声调拼音小写连写, 如 "nihao"）
  keyCount 条 key 记录:
      int16 keyLen, keyLen 字节 ASCII key
      int32 entryStart, entryCount   -- 在 entries 词记录数组中的区间 [start, start+count)
  int32 entryCount(=N)               -- 词记录总数（按 key 分组排序; 组内按 freq 降序）
  N 条词记录:
      int16 charLen, charLen*int16 UTF-16 码元
      int32 freq
  [QJL2 附加] 简拼索引区（简拼 -> key 下标列表，用于首字母联想）:
      int32 initialCount
      initialCount 条记录:
          int16 keyLen, keyLen 字节 ASCII 简拼（如 "zg"）
          int32 keyCount, keyCount 个 int32 keys_sorted 下标

运行时查找: keys 升序数组 -> 二分找 key / 前缀范围 -> 取组内 top 词。
"""
import struct
import sys
import re
from collections import defaultdict

PINYIN_TSV = "/tmp/lexicon/pinyin.tsv"
JIEBA_DICT = "/tmp/lexicon/jieba_dict.txt"
OUT_PATH = "app/src/main/assets/lexicon/lex.bin"

CJK_RE = re.compile(r'^[\u4e00-\u9fff]+$')

# ---------- 拼音归一化 ----------

TONE_MAP = {
    'ā':'a','á':'a','ǎ':'a','à':'a',
    'ō':'o','ó':'o','ǒ':'o','ò':'o',
    'ē':'e','é':'e','ě':'e','è':'e',
    'ī':'i','í':'i','ǐ':'i','ì':'i',
    'ū':'u','ú':'u','ǔ':'u','ù':'u',
    'ǖ':'v','ǘ':'v','ǚ':'v','ǜ':'v','ü':'v',
    'ń':'n','ň':'n','ǹ':'n',
    'ḿ':'m','m̀':'m',
}

def norm_syllable(py: str) -> str:
    """去声调 + ü→v；jqx 后的 v→u（ju/qu/xu 书写惯例）"""
    s = ""
    for ch in py:
        s += TONE_MAP.get(ch, ch)
    s = s.lower().strip()
    if not s:
        return ""
    # jqx + v -> u
    if s[0] in 'jqx' and 'v' in s:
        s = s.replace('v', 'u')
    return s

def main():
    # 1) 字 -> 拼音列表（首个为最常用读音）
    char_pinyins = {}
    with open(PINYIN_TSV, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            head, _, tail = line.partition('#')
            left = head.split(':', 1)
            if len(left) != 2:
                continue
            cp = left[0].strip()          # U+XXXX
            pys = left[1].strip()         # pin1,pin2
            if not cp.startswith('U+'):
                continue
            try:
                ch = chr(int(cp[2:], 16))
            except ValueError:
                continue
            readings = []
            for py in pys.split(','):
                n = norm_syllable(py)
                if n and n not in readings and n.isascii() and n.isalpha():
                    readings.append(n)
            if readings:
                char_pinyins[ch] = readings
    print(f"[1/4] 字拼音表: {len(char_pinyins)} 字", file=sys.stderr)

    # 2) jieba 词频: 词 -> freq（只保留纯 CJK 词，且每字都有拼音）
    words = {}   # word -> freq
    with open(JIEBA_DICT, encoding='utf-8') as f:
        for line in f:
            parts = line.strip().split(' ')
            if len(parts) < 2:
                continue
            w, freq = parts[0], parts[1]
            if not CJK_RE.match(w):
                continue
            try:
                fr = int(freq)
            except ValueError:
                continue
            if fr <= 0:
                continue
            if any(ch not in char_pinyins for ch in w):
                continue
            # 保留全部（freq 过滤在后面统一做）
            if fr > words.get(w, 0):
                words[w] = fr
    print(f"[2/4] jieba 纯 CJK 词: {len(words)}", file=sys.stderr)

    # 3) 组装 (word, key, freq, initials)
    #    单字: 全部字（含 jieba 词频; 非单字词的字给默认小词频 2，保证可打）
    #    多字词: freq >= 2 全保留（jieba CJK 约 30 万，控制规模在 8 字以内）
    #    initials: 多字词的简拼（每字主读音首字母连写，如 中国->zg），单字为 None
    entries = []  # (word, key, freq, initials)

    # 单字
    single_in_jieba = 0
    for w, fr in words.items():
        if len(w) == 1:
            ch = w
            for py in char_pinyins[ch][:1]:        # 单字词只用主读音（多读音靠 chars 自身多记录）
                entries.append((w, py, fr, None))
            single_in_jieba += 1
    for ch, pys in char_pinyins.items():
        if ch in words:
            continue
        for py in pys:                              # 非常用字所有读音都可打
            entries.append((ch, py, 2, None))
    print(f"    单字(jieba 有频): {single_in_jieba}, 单字记录累计: {sum(1 for e in entries if len(e[0])==1)}", file=sys.stderr)

    # 多字词
    multi = 0
    for w, fr in words.items():
        if len(w) < 2 or len(w) > 8:
            continue
        if fr < 2:
            continue
        # key: 每字取主读音连写；跳过轻声/儿化无法归一的
        # initials: 简拼 = 每字主读音首字母连写
        keys = ['']
        initials = ''
        ok = True
        for ch in w:
            py = char_pinyins[ch][0]
            if not py:
                ok = False
                break
            keys = [k + py for k in keys[:1]]
            initials += py[0]
        if not ok:
            continue
        entries.append((w, keys[0], fr, initials))
        multi += 1
    print(f"    多字词: {multi}", file=sys.stderr)

    # 4) 写二进制
    #    按 key 分组: key -> [(word, freq)]
    group = defaultdict(list)
    initials_of_key = defaultdict(set)   # key -> 简拼集合（多字词）
    for w, key, fr, ini in entries:
        group[key].append((w, fr))
        if ini:
            initials_of_key[key].add(ini)

    keys_sorted = sorted(group.keys())
    key_idx = {k: i for i, k in enumerate(keys_sorted)}
    # 每个 key 的最大词频：简拼 key 列表按词频降序排序，保证高频词（如"中国"）先被收集
    key_maxfreq = {}
    for k, lst in group.items():
        key_maxfreq[k] = max(fr for _, fr in lst)
    # 简拼 -> key 下标列表（升序去重，按组内最高词频降序）
    initials_map = defaultdict(list)
    for k, inis in initials_of_key.items():
        for ini in inis:
            initials_map[ini].append(key_idx[k])
    for ini, klist in initials_map.items():
        klist.sort(key=lambda ki: -key_maxfreq[keys_sorted[ki]])
    all_records = []   # (word, freq) 顺序与 key 区间对应
    key_metas = []     # (key, start, count)
    start = 0
    for k in keys_sorted:
        lst = group[k]
        lst.sort(key=lambda x: -x[1])
        all_records.extend(lst)
        key_metas.append((k, start, len(lst)))
        start += len(lst)

    buf = bytearray()
    buf += struct.pack('<4s', b'QJL2')
    buf += struct.pack('<i', len(key_metas))
    for k, s0, c in key_metas:
        kb = k.encode('ascii')
        buf += struct.pack('<h', len(kb)) + kb
        buf += struct.pack('<ii', s0, c)
    buf += struct.pack('<i', len(all_records))
    for w, fr in all_records:
        u16 = w.encode('utf-16-le')
        buf += struct.pack('<h', len(u16)//2) + u16
        buf += struct.pack('<i', fr)

    # 简拼索引区
    buf += struct.pack('<i', len(initials_map))
    for ini in sorted(initials_map):
        klist = initials_map[ini]
        kb = ini.encode('ascii')
        buf += struct.pack('<h', len(kb)) + kb
        buf += struct.pack('<i', len(klist))
        for ki in klist:
            buf += struct.pack('<i', ki)

    import os
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, 'wb') as f:
        f.write(buf)
    size_mb = len(buf) / 1024 / 1024
    print(f"[3/4] 词记录: {len(all_records)}, key 数: {len(key_metas)}, 简拼条数: {len(initials_map)}", file=sys.stderr)
    print(f"[4/4] 写出 {OUT_PATH}: {size_mb:.2f} MB", file=sys.stderr)

if __name__ == '__main__':
    main()
