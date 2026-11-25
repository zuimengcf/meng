# -*- coding: utf-8 -*-
# v4：种子 ≤4万行（给用户学习留 1 万行余量），每组至少 1 实词，虚词兜底
from collections import defaultdict

lines = open('/tmp/predict_dump.txt', encoding='utf-8').read().splitlines()
raw = []
for ln in lines:
    s, _, sc = ln.rpartition(' ')
    if '|' not in s:
        continue
    a, b = s.split('|', 1)
    try:
        score = float(sc)
    except ValueError:
        continue
    raw.append((a, b, score))

def is_han(c):
    return '\u4e00' <= c <= '\u9fff'

def han_count(w):
    return sum(1 for c in w if is_han(c))

FUNC = set('的了在是和与及或之且而并就有也都不个很到会能要对为从被把让给向于其')
def is_func(w):
    return len(w) == 1 and w in FUNC

# 过滤：code=纯1-2汉字, word 含≥1汉字且≤12字
kept = [(a, b, sc) for a, b, sc in raw
        if 1 <= len(a) <= 2 and han_count(a) == len(a)
        and han_count(b) >= 1 and len(b) <= 12]
print('过滤后:', len(kept))

# 分数归一化
scores = [sc for _, _, sc in kept]
mn, mx = min(scores), max(scores)
def real_freq(sc):
    t = (sc - mn) / (mx - mn)
    return max(1, round(t * 29) + 60)  # 实词 60..89

# 分组并按分数排序
bycode = defaultdict(list)
for a, b, sc in kept:
    bycode[a].append((b, sc))
for a in bycode:
    bycode[a].sort(key=lambda x: (-x[1], x[0]))

CAP = 70000  # 全量：70000 + 用户学习 10000 ≤ 80000 < 100000 native 上限

# 每组的实词表、虚词表
real_of = {}
func_of = {}
for a, items in bycode.items():
    real_of[a] = [(b, real_freq(sc)) for b, sc in items if not is_func(b)]
    func_of[a] = [(b, 2) for b, sc in items if is_func(b)]

# 第 1 轮：所有有实词的组保底 1 条实词（照频次取 top1）
sel = []
for a in bycode:
    if real_of[a]:
        sel.append((a, real_of[a][0][0], real_of[a][0][1]))
    else:
        # 只有虚词的组：放 1 条虚词兜底（如「你好→了」这类自然收尾）
        if func_of[a]:
            sel.append((a, func_of[a][0][0], func_of[a][0][1]))
print('第1轮保底后:', len(sel))

# 第 2 轮：轮转填充 —— 每轮给每个组补 1 条实词，公平分配到 CAP
used = set((a, b) for a, b, _ in sel)
remain_cap = CAP - len(sel)
# 每组的实词候选游标（top1 已放，从 top2 开始）
cursor = {a: 1 for a in bycode}
round_idx = 0
while remain_cap > 0:
    added_this_round = 0
    for a in bycode:
        if remain_cap <= 0:
            break
        i = cursor[a]
        while i < len(real_of[a]) and (a, real_of[a][i][0]) in used:
            i += 1
        if i < len(real_of[a]):
            b, f = real_of[a][i]
            sel.append((a, b, f))
            used.add((a, b))
            cursor[a] = i + 1
            remain_cap -= 1
            added_this_round += 1
    if added_this_round == 0:
        break
    round_idx += 1
print('第2轮轮转补足后:', len(sel), '（共', round_idx, '轮）')

# 合并原种子（种子频次 86-100 保持优先）
seed = open('/data/data/com.ai.assistance.operit/files/workspace/输入法/meng/app/src/main/assets/smart-input/suggestion.tsv', encoding='utf-8').read().splitlines()
seed_map = {}
for ln in seed:
    p = ln.split('\t')
    if len(p) >= 3:
        seed_map[(p[0], p[1])] = int(p[2])
merged = {}
for a, b, f in sel:
    merged[(a, b)] = seed_map.get((a, b), f)
for (a, b), f in seed_map.items():
    merged.setdefault((a, b), f)
out = [(a, b, f) for (a, b), f in merged.items()]
out.sort(key=lambda x: (-x[2], x[0], x[1]))
print('合并后总行数:', len(out), '（原种子', len(seed), '行全部保留)')

with open('/tmp/suggestion_final.tsv', 'w', encoding='utf-8') as f:
    for a, b, fr in out:
        f.write('%s\t%s\t%d\n' % (a, b, fr))
import os
print('输出 /tmp/suggestion_final.tsv: %.1f KB / %d 行' % (os.path.getsize('/tmp/suggestion_final.tsv') / 1024, len(out)))

print('\n=== 质量抽查 ===')
for code in ['热点', '体力', '兴起', '调', '金像', '值回', '回家', '你好', '泄露', '暖心', '工伤', '药业', '电脑', '手机', '学习', '天气', '工作', '考试', '股票', '医院']:
    items = [(b, f) for a, b, f in out if a == code][:6]
    if items:
        print('%s → %s' % (code, '  '.join('%s(%d)' % (b, f) for b, f in items)))
    else:
        print('%s → (无)' % code)

print('\n=== 全局统计 ===')
codes = set(a for a, b, f in out)
print('code 数:', len(codes))
func_rows = sum(1 for a, b, f in out if is_func(b))
print('虚词行:', func_rows, '(%.1f%%)' % (100 * func_rows / len(out)))
real_rows = len(out) - func_rows
print('实词行:', real_rows)
print('平均每组实词:', round(real_rows / len(codes), 2))
# 覆盖度：原 code 的保底率
print('覆盖 code 数/原 code 数: %d/%d (%.1f%%)' % (len(codes), len(bycode), 100 * len(codes) / len(bycode)))