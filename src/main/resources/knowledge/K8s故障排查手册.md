# K8s 故障排查手册

## Pod CrashLoopBackOff 问题排查

### 问题描述
Pod 反复重启，状态显示为 CrashLoopBackOff，表示容器启动后立即崩溃。

### 常见原因
1. **应用程序异常退出**：代码 bug、未捕获的异常、配置错误
2. **启动命令错误**：ENTRYPOINT 或 CMD 配置错误
3. **依赖服务不可用**：数据库、Redis、消息队列等服务未就绪
4. **资源不足**：内存 OOM、CPU 限流
5. **健康检查失败**：livenessProbe 配置过于严格

### 排查步骤

#### 1. 查看 Pod 事件
```bash
kubectl describe pod <pod-name> -n <namespace>
```
关注 Events 部分的错误信息，特别是 Back-off restarting failed container。

#### 2. 查看容器日志
```bash
# 查看当前容器日志
kubectl logs <pod-name> -n <namespace>

# 查看上一次崩溃的日志（重要）
kubectl logs <pod-name> -n <namespace> --previous
```

#### 3. 检查资源限制
```bash
kubectl top pod <pod-name> -n <namespace>
```
查看内存和 CPU 使用情况，确认是否触达 limits。

#### 4. 检查健康检查配置
查看 Pod YAML 中的 livenessProbe 和 readinessProbe 配置：
```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30  # 启动延迟要足够
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

### 解决方案

#### 方案 1：延长健康检查等待时间
如果应用启动较慢，增加 initialDelaySeconds：
```yaml
livenessProbe:
  initialDelaySeconds: 60  # 增加到 60 秒
```

#### 方案 2：增加资源配额
```yaml
resources:
  requests:
    memory: "256Mi"
    cpu: "500m"
  limits:
    memory: "512Mi"  # 增加内存限制
    cpu: "1000m"
```

#### 方案 3：修复应用代码
根据日志信息修复应用 bug，常见问题：
- 未捕获的 panic（Go）
- NullPointerException（Java）
- 配置文件路径错误
- 环境变量缺失

#### 方案 4：检查依赖服务
使用 initContainer 确保依赖服务就绪：
```yaml
initContainers:
- name: wait-for-db
  image: busybox
  command: ['sh', '-c', 'until nc -z mysql 3306; do sleep 1; done']
```

### 参考命令速查
```bash
# 快速重启 Pod
kubectl delete pod <pod-name> -n <namespace>

# 强制删除卡住的 Pod
kubectl delete pod <pod-name> -n <namespace> --force --grace-period=0

# 查看 Pod 详细信息（JSON 格式）
kubectl get pod <pod-name> -n <namespace> -o json
```

---

## Pod ImagePullBackOff 问题排查

### 问题描述
Pod 无法拉取镜像，状态显示 ImagePullBackOff 或 ErrImagePull。

### 常见原因
1. **镜像不存在**：镜像名称或 tag 错误
2. **私有仓库认证失败**：imagePullSecrets 配置错误
3. **网络问题**：无法访问镜像仓库（国内拉取 gcr.io、quay.io 常见）
4. **镜像仓库限流**：Docker Hub 匿名用户限流（200 次/6 小时）

### 排查步骤

#### 1. 查看事件详情
```bash
kubectl describe pod <pod-name> -n <namespace>
```
关注 Failed to pull image 相关错误信息。

#### 2. 验证镜像是否存在
```bash
# 在节点上手动拉取镜像
docker pull <image-name>:<tag>
```

#### 3. 检查 imagePullSecrets
```bash
kubectl get secret -n <namespace>
kubectl describe secret <secret-name> -n <namespace>
```

### 解决方案

#### 方案 1：修正镜像名称
确认镜像 tag 正确：
```yaml
spec:
  containers:
  - name: app
    image: registry.cn-hangzhou.aliyuncs.com/my-app:v1.2.3  # 确认 tag
```

#### 方案 2：配置镜像拉取凭证
创建 Docker registry secret：
```bash
kubectl create secret docker-registry my-registry-secret \
  --docker-server=registry.example.com \
  --docker-username=myuser \
  --docker-password=mypassword \
  --docker-email=my@example.com \
  -n <namespace>
```

在 Deployment 中引用：
```yaml
spec:
  imagePullSecrets:
  - name: my-registry-secret
```

#### 方案 3：使用国内镜像加速
替换为阿里云镜像仓库：
```yaml
# 原镜像：k8s.gcr.io/pause:3.2
# 替换为：registry.aliyuncs.com/google_containers/pause:3.2
```

#### 方案 4：配置节点镜像加速器
在 /etc/docker/daemon.json 中配置：
```json
{
  "registry-mirrors": [
    "https://dockerproxy.com",
    "https://mirror.baidubce.com"
  ]
}
```
重启 Docker：
```bash
systemctl restart docker
```

---

## Pod Pending 状态排查

### 问题描述
Pod 一直处于 Pending 状态，无法调度到节点上。

### 常见原因
1. **资源不足**：集群中没有满足 requests 的节点
2. **节点选择器不匹配**：nodeSelector 或 nodeAffinity 配置错误
3. **污点容忍度不匹配**：节点有 taint，Pod 没有 toleration
4. **PVC 挂载失败**：存储卷无法绑定

### 排查步骤

#### 1. 查看调度事件
```bash
kubectl describe pod <pod-name> -n <namespace>
```
关注 Warning FailedScheduling 事件。

#### 2. 检查节点资源
```bash
kubectl top nodes
kubectl describe nodes
```

#### 3. 检查 PVC 状态
```bash
kubectl get pvc -n <namespace>
kubectl describe pvc <pvc-name> -n <namespace>
```

### 解决方案

#### 方案 1：降低资源请求
```yaml
resources:
  requests:
    memory: "128Mi"  # 降低到合理值
    cpu: "100m"
```

#### 方案 2：添加节点或扩容
```bash
# 查看集群节点
kubectl get nodes

# 添加新节点到集群（依据云平台操作）
```

#### 方案 3：修正节点选择器
```yaml
spec:
  nodeSelector:
    disktype: ssd  # 确认集群中有此标签的节点
```

#### 方案 4：添加污点容忍度
```yaml
spec:
  tolerations:
  - key: "node-role.kubernetes.io/master"
    operator: "Exists"
    effect: "NoSchedule"
```

---

## Service 无法访问排查

### 问题描述
Service 创建成功，但无法通过 ClusterIP 或 NodePort 访问后端 Pod。

### 常见原因
1. **Endpoints 为空**：Pod 标签与 Service selector 不匹配
2. **Pod 未就绪**：readinessProbe 失败
3. **网络策略阻断**：NetworkPolicy 拒绝流量
4. **端口配置错误**：targetPort 与容器端口不一致

### 排查步骤

#### 1. 检查 Endpoints
```bash
kubectl get endpoints <service-name> -n <namespace>
```
如果 ENDPOINTS 列为空，说明没有 Pod 匹配。

#### 2. 验证标签匹配
```bash
# 查看 Service selector
kubectl get svc <service-name> -n <namespace> -o yaml | grep -A 5 selector

# 查看 Pod 标签
kubectl get pods -n <namespace> --show-labels
```

#### 3. 测试 Pod 直接访问
```bash
# 获取 Pod IP
kubectl get pod <pod-name> -n <namespace> -o wide

# 在集群内测试直接访问 Pod
kubectl run -it --rm debug --image=busybox --restart=Never -- wget -O- <pod-ip>:8080
```

### 解决方案

#### 方案 1：修正标签匹配
确保 Service selector 与 Pod labels 一致：
```yaml
# Service
selector:
  app: my-app
  version: v1

# Deployment
metadata:
  labels:
    app: my-app
    version: v1  # 必须完全匹配
```

#### 方案 2：修正端口配置
```yaml
spec:
  ports:
  - port: 80          # Service 暴露的端口
    targetPort: 8080  # Pod 容器的端口（必须一致）
    protocol: TCP
```

#### 方案 3：检查 readinessProbe
确保健康检查路径正确：
```yaml
readinessProbe:
  httpGet:
    path: /health  # 确认应用确实有此端点
    port: 8080
```

#### 方案 4：检查网络策略
```bash
kubectl get networkpolicy -n <namespace>

# 如果有策略，检查是否允许流量
kubectl describe networkpolicy <policy-name> -n <namespace>
```

### 参考命令
```bash
# 临时暴露 Pod 用于测试
kubectl port-forward pod/<pod-name> 8080:8080 -n <namespace>

# 查看 Service 的完整配置
kubectl get svc <service-name> -n <namespace> -o yaml
```

---

## PVC 无法绑定排查

### 问题描述
PersistentVolumeClaim 一直处于 Pending 状态，无法绑定到 PV。

### 常见原因
1. **没有可用的 PV**：集群中没有满足条件的 PersistentVolume
2. **StorageClass 不存在**：PVC 指定的 storageClassName 不存在
3. **容量不匹配**：PVC 请求的容量大于所有可用 PV
4. **访问模式不兼容**：accessModes 不匹配（ReadWriteOnce vs ReadWriteMany）

### 排查步骤

#### 1. 查看 PVC 状态
```bash
kubectl describe pvc <pvc-name> -n <namespace>
```
关注 Events 部分的绑定失败原因。

#### 2. 检查 StorageClass
```bash
kubectl get storageclass
kubectl describe storageclass <sc-name>
```

#### 3. 查看可用 PV
```bash
kubectl get pv
```
查看状态为 Available 的 PV。

### 解决方案

#### 方案 1：创建匹配的 PV（静态供应）
```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: my-pv
spec:
  capacity:
    storage: 10Gi
  accessModes:
  - ReadWriteOnce
  storageClassName: manual  # 与 PVC 一致
  hostPath:
    path: /mnt/data
```

#### 方案 2：使用动态供应
配置 StorageClass（云环境通常已内置）：
```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-ssd
provisioner: kubernetes.io/aws-ebs  # 依云平台而定
parameters:
  type: gp3
```

PVC 引用：
```yaml
spec:
  storageClassName: fast-ssd
  accessModes:
  - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
```

#### 方案 3：调整访问模式
```yaml
spec:
  accessModes:
  - ReadWriteOnce  # 单节点读写（最常用）
  # - ReadWriteMany  # 多节点读写（需 NFS 等）
```

---

📚 **参考来源**：K8s 官方故障排查文档、运维实战经验总结
