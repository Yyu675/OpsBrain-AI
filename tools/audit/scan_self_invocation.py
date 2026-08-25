import re, glob, os
issues=[]
for f in glob.glob('src/main/java/**/*.java', recursive=True):
    src=open(f,encoding='utf-8').read()
    lines=src.split('\n')
    # 收集 @Transactional 标注的方法名
    tx=set()
    for i,l in enumerate(lines):
        if '@Transactional' in l:
            for j in range(i+1, min(i+8, len(lines))):
                m=re.search(r'public\s+[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(', lines[j])
                if m:
                    tx.add(m.group(1)); break
    if not tx: continue
    # 找出方法边界，判断调用者自身是否有 @Transactional
    cur=None; cur_tx=False
    for i,l in enumerate(lines):
        m=re.search(r'^\s{4}public\s+[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(', l)
        if m:
            cur=m.group(1)
            cur_tx=any('@Transactional' in lines[k] for k in range(max(0,i-8), i))
        if cur and not cur_tx:
            for t in tx:
                if t==cur: continue
                if re.search(r'(?<![\w.])'+t+r'\s*\(', l) and 'public' not in l:
                    issues.append((os.path.basename(f), i+1, cur, t, l.strip()[:70]))
seen=set(); out=[]
for it in issues:
    k=(it[0],it[2],it[3])
    if k in seen: continue
    seen.add(k); out.append(it)
print(f"疑似自调用导致事务失效：{len(out)} 处\n")
for f,ln,caller,callee,code in out:
    print(f"  {f}:{ln}\n    非事务方法 {caller}() 自调用 @Transactional 的 {callee}()\n    > {code}\n")
