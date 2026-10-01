#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""将白霜拼音词库转为 build_lexicon.py 所需的 jieba_dict.txt 格式。"""
import os, re, glob, shutil

CN_DICTS_DIR = os.path.expanduser("~/workspace/rime-frost/cn_dicts")
OUT_PATH = "/tmp/lexicon/jieba_dict.txt"
BACKUP_PATH = "/tmp/lexicon/jieba_dict.txt.bak"
CJK_RE = re.compile(r'^[\u4e00-\u9fff]+$')
DEFAULT_FREQ = 100

def parse_dict_yaml(path):
    entries, in_body = [], False
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not in_body:
                if line.strip() == '...':
                    in_body = True
                continue
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            word = parts[0].strip()
            freq = 0
            if len(parts) >= 3:
                try:
                    freq = int(parts[-1])
                except ValueError:
                    freq = 0
            entries.append((word, freq))
    return entries

def main():
    all_words = {}
    for path in sorted(glob.glob(os.path.join(CN_DICTS_DIR, "*.dict.yaml"))):
        entries = parse_dict_yaml(path)
        kept = 0
        for word, freq in entries:
            if not CJK_RE.match(word):
                continue
            if len(word) < 1 or len(word) > 8:
                continue
            kept += 1
            if freq > all_words.get(word, -1):
                all_words[word] = freq
        print("  %s: %d 条 -> 有效 %d 条" % (os.path.basename(path), len(entries), kept))
    print("  去重后总词数: %d" % len(all_words))
    zero = sum(1 for f in all_words.values() if f <= 0)
    for w in all_words:
        if all_words[w] <= 0:
            all_words[w] = DEFAULT_FREQ
    print("  词频为0(赋默认值 %d): %d 条" % (DEFAULT_FREQ, zero))
    result = sorted(all_words.items(), key=lambda x: -x[1])
    if os.path.exists(OUT_PATH) and not os.path.exists(BACKUP_PATH):
        shutil.copy2(OUT_PATH, BACKUP_PATH)
        print("  已备份原文件到 %s" % BACKUP_PATH)
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, 'w', encoding='utf-8') as f:
        for word, freq in result:
            f.write("%s %d n\n" % (word, freq))
    print("  输出: %s, 总词数: %d" % (OUT_PATH, len(result)))

if __name__ == '__main__':
    main()
