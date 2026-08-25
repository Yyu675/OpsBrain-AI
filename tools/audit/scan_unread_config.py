import re, glob, os
print("=== 扫描3：配置项定义了但无代码读取（同 P1-7 那类缺陷）===")
# 收集 application.yml 里 devops.* 的叶子键
import itertools
ymls=['src/main/resources/application.yml']
keys=[]
for y in ymls:
    stack=[]
    for line in open(y,encoding='utf-8'):
        if not line.strip() or line.strip().startswith('#'): continue
        indent=len(line)-len(line.lstrip())
        m=re.match(r'\s*([a-z0-9\-]+):\s*(.*)$', line)
        if not m: continue
        k,v=m.group(1),m.group(2).strip()
        stack=[(i,kk) for i,kk in stack if i<indent]
        stack.append((indent,k))
        if v and not v.startswith('#'):
            keys.append('.'.join(kk for _,kk in stack))
src_all=''
for f in glob.glob('src/main/java/**/*.java', recursive=True):
    src_all+=open(f,encoding='utf-8').read()
missing=[]
for k in keys:
    if not k.startswith('devops.'): continue
    if k in src_all: continue
    # @Value 里可能用 ${a.b:default}
    if re.search(re.escape(k)+r'[:\}]', src_all): continue
    missing.append(k)
print(f"  devops.* 叶子配置 {len([k for k in keys if k.startswith('devops.')])} 个，无代码引用 {len(missing)} 个：")
for k in missing: print(f"    {k}")
