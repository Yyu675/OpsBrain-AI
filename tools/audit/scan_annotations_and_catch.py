import re, glob, os
print("=== 扫描1：@Transactional 标在 private/protected 方法上（完全不生效）===")
n=0
for f in glob.glob('src/main/java/**/*.java', recursive=True):
    lines=open(f,encoding='utf-8').read().split('\n')
    for i,l in enumerate(lines):
        if '@Transactional' in l:
            for j in range(i+1, min(i+8, len(lines))):
                s=lines[j].strip()
                if re.match(r'(private|protected)\s', s):
                    print(f"  {os.path.basename(f)}:{j+1}  {s[:70]}"); n+=1; break
                if re.match(r'(public|@)', s): break
print(f"  合计 {n} 处\n")

print("=== 扫描2：catch 块吞掉异常且无日志（静默失败）===")
n=0
for f in glob.glob('src/main/java/**/*.java', recursive=True):
    src=open(f,encoding='utf-8').read()
    for m in re.finditer(r'catch\s*\([^)]*\)\s*\{([^{}]*)\}', src):
        body=m.group(1).strip()
        if not body: 
            line=src[:m.start()].count('\n')+1
            print(f"  {os.path.basename(f)}:{line}  空 catch"); n+=1
        elif 'log' not in body and 'throw' not in body and 'return' not in body and len(body)<80:
            line=src[:m.start()].count('\n')+1
            print(f"  {os.path.basename(f)}:{line}  {body[:60]}"); n+=1
print(f"  合计 {n} 处\n")
