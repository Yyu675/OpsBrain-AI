import re, glob, os
print("=== 扫描4：Service 层 public 写方法 vs 测试覆盖 ===")
tests=''
for f in glob.glob('src/test/**/*.java', recursive=True):
    tests+=open(f,encoding='utf-8').read()
rows=[]
for f in glob.glob('src/main/java/**/*Service*.java', recursive=True)+glob.glob('src/main/java/**/*Manager.java', recursive=True):
    if '/test/' in f: continue
    src=open(f,encoding='utf-8').read()
    cls=os.path.basename(f).replace('.java','')
    if 'interface '+cls in src: continue
    writes=[]
    for m in re.finditer(r'public\s+[\w<>,\.\[\]\s]+?\s+(\w+)\s*\(', src):
        name=m.group(1)
        if name==cls: continue
        if re.match(r'^(get|find|list|count|is|has|describe|all|query)', name): continue
        writes.append(name)
    writes=sorted(set(writes))
    if not writes: continue
    untested=[w for w in writes if not re.search(r'\.'+w+r'\s*\(', tests)]
    if untested:
        rows.append((len(untested), len(writes), cls, untested))
rows.sort(reverse=True)
for u,t,cls,names in rows:
    print(f"  {cls}: {u}/{t} 个写方法无测试")
    print(f"     {', '.join(names[:12])}{' ...' if len(names)>12 else ''}")
